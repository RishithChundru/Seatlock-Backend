package com.seatlock.service;

import com.seatlock.api.Dtos.BookingDto;
import com.seatlock.domain.*;
import com.seatlock.domain.Repositories.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class BookingService {
    public static class SeatUnavailableException extends RuntimeException {
        public SeatUnavailableException() { super("One or more selected seats were just booked by someone else"); }
    }

    /** Carries everything the after-commit work needs, so that work never borrows a second DB connection. */
    public record BookingCommitted(Long bookingId, Long userId, Long eventId, List<Long> seatIds,
                                   String email, String eventTitle, List<String> seatLabels) {}
    public record BookingCancelled(Long bookingId, Long eventId, List<Long> seatIds) {}

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final UserRepository users;
    private final SeatHoldService holds;
    private final WaitlistRepository waitlist;
    private final ApplicationEventPublisher publisher;

    public BookingService(BookingRepository bookings, SeatRepository seats, EventRepository events, UserRepository users,
                          SeatHoldService holds, WaitlistRepository waitlist, ApplicationEventPublisher publisher) {
        this.bookings = bookings;
        this.seats = seats;
        this.events = events;
        this.users = users;
        this.holds = holds;
        this.waitlist = waitlist;
        this.publisher = publisher;
    }

    /**
     * Books seats atomically. Two concurrent requests for the same seat both read version N;
     * the second UPDATE ... WHERE version = N matches 0 rows and Hibernate throws an optimistic lock
     * failure, so the whole transaction (booking row included) rolls back.
     */
    @Transactional
    public BookingDto book(Long userId, Long eventId, List<Long> seatIds, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Booking> previous = bookings.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (previous.isPresent()) return BookingDto.of(previous.get());
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) throw new IllegalArgumentException("Duplicate seats in request");

        Event event = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found"));
        AppUser user = users.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found"));
        List<Seat> selected = seats.findAllById(seatIds);
        if (selected.size() != seatIds.size() || selected.stream().anyMatch(s -> !s.getEvent().getId().equals(eventId)))
            throw new IllegalArgumentException("Seats do not belong to this event");
        if (selected.stream().anyMatch(s -> s.getStatus() != Seat.Status.AVAILABLE))
            throw new SeatUnavailableException();

        holds.assertBookable(userId, seatIds);

        Booking booking = bookings.save(new Booking(user, event, event.getPriceCents() * selected.size(), idempotencyKey));
        selected.forEach(s -> s.book(booking));
        booking.recordSeatLabels();
        seats.saveAllAndFlush(selected); // version check fires here, inside the transaction
        waitlist.deleteByEventIdAndUserId(eventId, userId); // got a seat: no longer waiting for this event
        // Delivered only after commit (see BookingEvents): evict cache, release holds, publish to RabbitMQ.
        publisher.publishEvent(new BookingCommitted(booking.getId(), userId, eventId, seatIds,
                user.getEmail(), event.getTitle(), booking.seatLabels()));
        return BookingDto.of(booking);
    }

    /**
     * Cancels the caller's own booking before the event starts and puts its seats back on sale.
     * Idempotent: cancelling twice returns the already-cancelled booking. After commit, the freed
     * seats go to RabbitMQ so the waitlist gets first claim (see BookingEvents / WaitlistConsumer).
     */
    @Transactional
    public BookingDto cancel(Long userId, Long bookingId) {
        // Someone else's booking id looks exactly like a missing one: no probing other users' bookings.
        Booking booking = bookings.findById(bookingId).filter(b -> b.getUser().getId().equals(userId))
                .orElseThrow(() -> new NoSuchElementException("Booking not found"));
        if (booking.isCancelled()) return BookingDto.of(booking);
        if (!booking.getEvent().getStartsAt().isAfter(Instant.now()))
            throw new IllegalArgumentException("This event has already started and can't be cancelled");

        List<Seat> freed = booking.cancel();
        seats.saveAllAndFlush(freed);
        publisher.publishEvent(new BookingCancelled(booking.getId(), booking.getEvent().getId(), freed.stream().map(Seat::getId).toList()));
        return BookingDto.of(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> forUser(Long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId).stream().map(BookingDto::of).toList();
    }
}
