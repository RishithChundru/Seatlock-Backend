package com.seatlock.service;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.Event;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class EventService {
    public static final String LIST_CACHE = "events";
    public static final String ITEM_CACHE = "event";

    private final EventRepository events;
    private final SeatRepository seats;
    private final SeatHoldService holds;

    public EventService(EventRepository events, SeatRepository seats, SeatHoldService holds) {
        this.events = events;
        this.seats = seats;
        this.holds = holds;
    }

    @Cacheable(LIST_CACHE)
    @Transactional(readOnly = true)
    public List<EventDto> list() {
        // shortcut: 2 count queries per event, fine for dozens of events (and cached); use one GROUP BY query when it grows
        return new ArrayList<>(events.findAllByOrderByStartsAtAsc().stream().map(this::toDto).toList());
    }

    @Cacheable(ITEM_CACHE)
    @Transactional(readOnly = true)
    public EventDto get(Long id) {
        return toDto(events.findById(id).orElseThrow(() -> new NoSuchElementException("Event not found")));
    }

    /** Not cached: holds change every few seconds and the map must be live. */
    @Transactional(readOnly = true)
    public List<SeatDto> seats(Long eventId, Long viewerId) {
        if (!events.existsById(eventId)) throw new NoSuchElementException("Event not found");
        List<Seat> list = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId);
        Map<Long, Long> held = holds.holders(list.stream().map(Seat::getId).toList());
        return list.stream().map(s -> {
            Long holder = held.get(s.getId());
            boolean booked = s.getStatus() == Seat.Status.BOOKED;
            String status = booked ? "BOOKED" : holder != null ? "HELD" : "AVAILABLE";
            return new SeatDto(s.getId(), s.getRowLabel(), s.getSeatNumber(), status, !booked && holder != null && holder.equals(viewerId));
        }).toList();
    }

    @Caching(evict = {@CacheEvict(value = LIST_CACHE, allEntries = true), @CacheEvict(value = ITEM_CACHE, allEntries = true)})
    @Transactional
    public EventDto create(CreateEventRequest r) {
        Event e = events.save(new Event(r.title(), r.venue(), r.category(), r.startsAt(), r.priceCents()));
        List<Seat> grid = new ArrayList<>();
        for (int row = 0; row < r.rows(); row++)
            for (int n = 1; n <= r.seatsPerRow(); n++)
                grid.add(new Seat(e, String.valueOf((char) ('A' + row)), n));
        seats.saveAll(grid);
        return toDto(e);
    }

    private EventDto toDto(Event e) {
        return new EventDto(e.getId(), e.getTitle(), e.getVenue(), e.getCategory(), e.getStartsAt(), e.getPriceCents(),
                seats.countByEventId(e.getId()), seats.countByEventIdAndStatus(e.getId(), Seat.Status.AVAILABLE));
    }
}
