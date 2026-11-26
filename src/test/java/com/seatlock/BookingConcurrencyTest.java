package com.seatlock;

import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seatlock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BookingConcurrencyTest {
    @Autowired BookingService bookings;
    @Autowired SeatRepository seats;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired BookingRepository bookingRepo;

    private Seat freeSeat(int skip) {
        Long eventId = events.findAllByOrderByStartsAtAsc().get(0).getId();
        return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).stream()
                .filter(s -> s.getStatus() == Seat.Status.AVAILABLE).skip(skip).findFirst().orElseThrow();
    }

    @Test
    void fiftyConcurrentRequestsForOneSeatProduceExactlyOneBooking() throws Exception {
        Seat seat = freeSeat(0);
        Long eventId = seat.getEvent().getId();
        Long userId = users.findByEmail("demo@seatlock.dev").orElseThrow().getId();
        long bookingsBefore = bookingRepo.count();

        int threads = 50;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var ok = new AtomicInteger();
        var rejected = new AtomicInteger();
        List<Future<?>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    bookings.book(userId, eventId, List.of(seat.getId()), UUID.randomUUID().toString());
                    ok.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException | BookingService.SeatUnavailableException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS); // surfaces any unexpected exception
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(seats.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(Seat.Status.BOOKED);
        assertThat(bookingRepo.count()).isEqualTo(bookingsBefore + 1); // losers' booking rows rolled back
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameBooking() {
        Seat seat = freeSeat(1);
        Long userId = users.findByEmail("demo@seatlock.dev").orElseThrow().getId();
        String key = UUID.randomUUID().toString();

        var first = bookings.book(userId, seat.getEvent().getId(), List.of(seat.getId()), key);
        var retry = bookings.book(userId, seat.getEvent().getId(), List.of(seat.getId()), key);

        assertThat(retry.id()).isEqualTo(first.id());
    }
}
