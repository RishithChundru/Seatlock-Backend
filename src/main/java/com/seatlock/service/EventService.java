package com.seatlock.service;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.Event;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class EventService {
    private final EventRepository events;
    private final SeatRepository seats;

    public EventService(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @Transactional(readOnly = true)
    public List<EventDto> list() {
        // shortcut: 2 count queries per event, fine for dozens of events; use one GROUP BY query when it grows
        return events.findAllByOrderByStartsAtAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public EventDto get(Long id) {
        return toDto(events.findById(id).orElseThrow(() -> new NoSuchElementException("Event not found")));
    }

    @Transactional(readOnly = true)
    public List<SeatDto> seats(Long eventId) {
        if (!events.existsById(eventId)) throw new NoSuchElementException("Event not found");
        return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).stream().map(SeatDto::of).toList();
    }

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
