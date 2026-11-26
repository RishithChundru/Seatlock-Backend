package com.seatlock;

import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.BookingService;
import com.seatlock.service.SeatHoldService;
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

    /** A seat that is free in the DB and not held in Redis, so tests never interfere with each other. */
    private Seat freeSeat() {
        Long eventId = events.findAllByOrderByStartsAtAsc().get(0).getId();
        List<Seat> available = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).stream()
                .filter(s -> s.getStatus() == Seat.Status.AVAILABLE).toList();
        var held = holds.holders(available.stream().map(Seat::getId).toList()).keySet();
        return available.stream().filter(s -> !held.contains(s.getId())).findFirst().orElseThrow();
    }

    private Long demoUser() { return users.findByEmail("demo@seatlock.dev").orElseThrow().getId(); }
    private Long adminUser() { return users.findByEmail("admin@seatlock.dev").orElseThrow().getId(); }

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
            bookings.book(demoUser(), eventId, List.of(seat.getId()), UUID.randomUUID().toString());
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
        holds.hold(adminUser(), eventId, List.of(seat.getId()));

        assertThatThrownBy(() -> bookings.book(demoUser(), eventId, List.of(seat.getId()), null))
                .isInstanceOf(BookingService.SeatUnavailableException.class);
        bookings.book(adminUser(), eventId, List.of(seat.getId()), null); // the holder can
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameBooking() {
        Seat seat = freeSeat();
        String key = UUID.randomUUID().toString();

        var first = bookings.book(demoUser(), seat.getEvent().getId(), List.of(seat.getId()), key);
        var retry = bookings.book(demoUser(), seat.getEvent().getId(), List.of(seat.getId()), key);

        assertThat(retry.id()).isEqualTo(first.id());
    }

    @Test
    void ticketIsIssuedAsynchronouslyViaRabbitMq() throws Exception {
        Seat seat = freeSeat();
        var booking = bookings.book(demoUser(), seat.getEvent().getId(), List.of(seat.getId()), null);
        assertThat(booking.ticketCode()).isNull(); // not issued in the request path

        String code = null;
        for (int i = 0; i < 100 && code == null; i++) {
            Thread.sleep(100);
            code = bookingRepo.findById(booking.id()).orElseThrow().getTicketCode();
        }
        assertThat(code).matches("SL-[A-Z2-9]{8}");
    }
}
