package com.seatlock.service;

import com.seatlock.domain.Event;
import com.seatlock.domain.Repositories.EventRepository;
import com.seatlock.domain.Repositories.SeatRepository;
import com.seatlock.domain.Seat;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The database half of a seat map (which seats exist and which are sold), cached in Redis.
 * It changes only when a booking or cancellation commits, and BookingEvents evicts it right then;
 * live holds are overlaid from Redis on every request. Found by load testing: uncached, every
 * seat-map view needed a pooled DB connection and 400 req/s starved a 5-connection pool.
 * A stale entry can only make a hold attempt optimistic; the @Version check at booking time decides.
 */
@Service
public class SeatRows {
    public static final String CACHE = "seatRows";

    public record SeatRow(Long id, String row, int number, boolean booked, Seat.Tier tier, int priceCents) implements Serializable {}

    private final SeatRepository seats;
    private final EventRepository events;

    public SeatRows(SeatRepository seats, EventRepository events) {
        this.seats = seats;
        this.events = events;
    }

    @Cacheable(value = CACHE, sync = true)
    public List<SeatRow> forEvent(Long eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found"));
        List<SeatRow> rows = new ArrayList<>();
        for (Seat s : seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId))
            rows.add(new SeatRow(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getStatus() == Seat.Status.BOOKED,
                    s.getTier(), event.priceFor(s.getTier())));
        return rows;
    }
}
