package com.seatlock;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.BookingService;
import com.seatlock.service.SeatHoldService;
import com.seatlock.service.EventService;
import com.seatlock.api.Dtos.CreateEventRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class BookingConcurrencyTest {
    static final int THREADS = 50;

    @Autowired BookingService bookings;
    @Autowired SeatHoldService holds;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired BookingRepository bookingRepo;
    @Autowired EventService eventService;

    /**
     * A seat on a brand-new event of its own. Test classes share one database and run in filesystem
     * order (arbitrary on Linux CI), so never pick "the first event" another test may have sold out.
     */
    private Seat freeSeat() {
        var ev = eventService.create(new CreateEventRequest("Race " + UUID.randomUUID(), "Hall, Test City", "Concert",
                Instant.now().plus(Duration.ofDays(7)), 1000, 1, 2, null));
        return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(ev.id()).get(0);
    }

    /** Tests create their own customers; synchronized because race() calls this from many threads. */
    private synchronized Long user(String email) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(new AppUser(email, "Test User", "not-a-real-hash", AppUser.Role.USER)))
                .getId();
    }
    private Long alice() { return user("alice@test.local"); }
    private Long bob() { return user("bob@test.local"); }

    /** Runs task THREADS times, all released at the same instant; returns how many succeeded. */
    private int race(Callable<Void> task, Class<?>... expectedFailures) throws Exception {
        var pool = Executors.newFixedThreadPool(THREADS);
        var start = new CountDownLatch(1);
        var ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.call();
                    ok.incrementAndGet();
                } catch (Exception e) {
                    if (List.of(expectedFailures).stream().noneMatch(c -> c.isInstance(e))) throw e;
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS); // surfaces any unexpected exception
        pool.shutdown();
        return ok.get();
    }

    @Test
    void fiftyConcurrentBookingsForOneSeatProduceExactlyOneBooking() throws Exception {
        Seat seat = freeSeat();
        Long eventId = seat.getEvent().getId();
        long before = bookingRepo.count();

        int ok = race(() -> {
            bookings.book(alice(), eventId, List.of(seat.getId()), UUID.randomUUID().toString());
            return null;
        }, ObjectOptimisticLockingFailureException.class, BookingService.SeatUnavailableException.class);

        assertThat(ok).isEqualTo(1);
        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(Seat.Status.BOOKED);
        assertThat(bookingRepo.count()).isEqualTo(before + 1); // losers' booking rows rolled back
    }

    @Test
    void fiftyConcurrentHoldsForOneSeatProduceExactlyOneHolder() throws Exception {
        Seat seat = freeSeat();
        Long eventId = seat.getEvent().getId();
        var userIds = new ConcurrentLinkedQueue<Long>();
        for (long i = 0; i < THREADS; i++) userIds.add(10_000 + i); // 50 distinct users

        int ok = race(() -> {
            holds.hold(userIds.poll(), eventId, List.of(seat.getId()));
            return null;
        }, BookingService.SeatUnavailableException.class);

        assertThat(ok).isEqualTo(1);
        assertThat(holds.holders(List.of(seat.getId()))).hasSize(1);
    }

    @Test
    void cannotBookASeatSomeoneElseIsHolding() {
        Seat seat = freeSeat();
        Long eventId = seat.getEvent().getId();
        holds.hold(bob(), eventId, List.of(seat.getId()));

        assertThatThrownBy(() -> bookings.book(alice(), eventId, List.of(seat.getId()), null))
                .isInstanceOf(BookingService.SeatUnavailableException.class);
        bookings.book(bob(), eventId, List.of(seat.getId()), null); // the holder can
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameBooking() {
        Seat seat = freeSeat();
        String key = UUID.randomUUID().toString();

        var first = bookings.book(alice(), seat.getEvent().getId(), List.of(seat.getId()), key);
        var retry = bookings.book(alice(), seat.getEvent().getId(), List.of(seat.getId()), key);

        assertThat(retry.id()).isEqualTo(first.id());
    }

    @Test
    void ticketIsIssuedAsynchronouslyViaRabbitMq() throws Exception {
        Seat seat = freeSeat();
        var booking = bookings.book(alice(), seat.getEvent().getId(), List.of(seat.getId()), null);
        assertThat(booking.ticketCode()).isNull(); // not issued in the request path

        String code = null;
        for (int i = 0; i < 100 && code == null; i++) {
            Thread.sleep(100);
            code = bookingRepo.findById(booking.id()).orElseThrow().getTicketCode();
        }
        assertThat(code).matches("SL-[A-Z2-9]{8}");
    }
}
