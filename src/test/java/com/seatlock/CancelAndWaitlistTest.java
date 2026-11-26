package com.seatlock;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.api.Dtos.EventDto;
import com.seatlock.api.Dtos.WaitlistStatus;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class CancelAndWaitlistTest {
    @Autowired BookingService bookings;
    @Autowired WaitlistService waitlist;
    @Autowired SeatHoldService holds;
    @Autowired EventService events;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;

    private Long user(String name) {
        String email = name + "-" + UUID.randomUUID() + "@test.local";
        return users.save(new AppUser(email, name, "not-a-real-hash", AppUser.Role.USER)).getId();
    }

    /** A small event of its own so tests never share seats. */
    private EventDto event(int seatCount, Instant startsAt) {
        return events.create(new CreateEventRequest("Test " + UUID.randomUUID(), "Hall", "Concert", startsAt, 50000, 1, seatCount, null));
    }

    private List<Long> seatIds(Long eventId) {
        return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).stream().map(Seat::getId).toList();
    }

    private static <T> T waitFor(Supplier<T> probe, java.util.function.Predicate<T> done) throws InterruptedException {
        T value = probe.get();
        for (int i = 0; i < 100 && !done.test(value); i++) {
            Thread.sleep(100);
            value = probe.get();
        }
        return value;
    }

    @Test
    void cancellingFreesSeatsAndOffersThemToTheWaitlistInOrder() throws Exception {
        EventDto ev = event(2, Instant.now().plus(Duration.ofDays(3)));
        List<Long> ids = seatIds(ev.id());
        Long alice = user("alice"), bob = user("bob"), carol = user("carol"), dave = user("dave");

        var booking = bookings.book(alice, ev.id(), ids, null);
        assertThat(waitlist.join(bob, ev.id()).position()).isEqualTo(1);
        assertThat(waitlist.join(carol, ev.id()).position()).isEqualTo(2);

        var cancelled = bookings.cancel(alice, booking.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.seats()).hasSize(2); // still shows what was booked
        assertThat(seats.findAllById(ids)).allMatch(s -> s.getStatus() == Seat.Status.AVAILABLE);

        // RabbitMQ consumer: one freed seat held for each person in line, first come first served.
        WaitlistStatus bobStatus = waitFor(() -> waitlist.statusFor(bob, ev.id()), s -> s.offeredSeat() != null);
        WaitlistStatus carolStatus = waitFor(() -> waitlist.statusFor(carol, ev.id()), s -> s.offeredSeat() != null);
        assertThat(bobStatus.offeredSeat()).isEqualTo("A1");
        assertThat(carolStatus.offeredSeat()).isEqualTo("A2");
        assertThat(holds.holders(ids)).containsEntry(ids.get(0), bob).containsEntry(ids.get(1), carol);

        // Nobody else can take an offered seat; the person it's offered to can.
        assertThatThrownBy(() -> bookings.book(dave, ev.id(), List.of(ids.get(0)), null))
                .isInstanceOf(BookingService.SeatUnavailableException.class);
        assertThat(bookings.book(bob, ev.id(), List.of(ids.get(0)), null).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelIsOwnerOnlyAndIdempotent() {
        EventDto ev = event(1, Instant.now().plus(Duration.ofDays(3)));
        Long alice = user("alice"), mallory = user("mallory");
        var booking = bookings.book(alice, ev.id(), seatIds(ev.id()), null);

        assertThatThrownBy(() -> bookings.cancel(mallory, booking.id())).isInstanceOf(NoSuchElementException.class);
        assertThat(bookings.cancel(alice, booking.id()).status()).isEqualTo("CANCELLED");
        assertThat(bookings.cancel(alice, booking.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void cannotCancelAfterTheEventStarts() {
        EventDto ev = event(1, Instant.now().minus(Duration.ofMinutes(5)));
        Long alice = user("alice");
        var booking = bookings.book(alice, ev.id(), seatIds(ev.id()), null);

        assertThatThrownBy(() -> bookings.cancel(alice, booking.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waitlistIsOnlyForSoldOutEvents() {
        EventDto ev = event(2, Instant.now().plus(Duration.ofDays(3)));
        assertThatThrownBy(() -> waitlist.join(user("bob"), ev.id()))
                .isInstanceOf(WaitlistService.SeatsStillAvailableException.class);
    }
}
