package com.seatlock.service;

import com.seatlock.api.Dtos.BookingDto;
import com.seatlock.domain.*;
import com.seatlock.domain.Repositories.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class BookingService {
    public static class SeatUnavailableException extends RuntimeException {
        public SeatUnavailableException() { super("One or more selected seats were just booked by someone else"); }
    }

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final UserRepository users;

    public BookingService(BookingRepository bookings, SeatRepository seats, EventRepository events, UserRepository users) {
        this.bookings = bookings;
        this.seats = seats;
        this.events = events;
        this.users = users;
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

        Booking booking = bookings.save(new Booking(user, event, event.getPriceCents() * selected.size(), idempotencyKey));
        selected.forEach(s -> s.book(booking));
        seats.saveAllAndFlush(selected); // version check fires here, inside the transaction
        return BookingDto.of(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> forUser(Long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId).stream().map(BookingDto::of).toList();
    }
}
