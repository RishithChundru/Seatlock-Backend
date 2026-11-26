package com.seatlock.service;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.Event;
import com.seatlock.domain.EventImage;
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

    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private final EventRepository events;
    private final SeatRepository seats;
    private final SeatHoldService holds;
    private final EventImageRepository images;

    public EventService(EventRepository events, SeatRepository seats, SeatHoldService holds, EventImageRepository images) {
        this.events = events;
        this.seats = seats;
        this.holds = holds;
        this.images = images;
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

    @Caching(evict = {@CacheEvict(value = LIST_CACHE, allEntries = true), @CacheEvict(value = ITEM_CACHE, key = "#eventId")})
    @Transactional
    public EventDto setImage(Long eventId, byte[] data) {
        Event e = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found"));
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image must be under 2 MB");
        String type = sniffImageType(data);
        if (type == null) throw new IllegalArgumentException("Only JPEG, PNG or WebP images are allowed");
        images.save(new EventImage(eventId, type, data));
        e.imageChanged();
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public EventImage image(Long eventId) {
        return images.findById(eventId).orElseThrow(() -> new NoSuchElementException("No image for this event"));
    }

    /** Trust the file's magic bytes, never the client's Content-Type (blocks SVG/HTML posing as an image). */
    static String sniffImageType(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        return null;
    }

    @Caching(evict = {@CacheEvict(value = LIST_CACHE, allEntries = true), @CacheEvict(value = ITEM_CACHE, allEntries = true)})
    @Transactional
    public EventDto create(CreateEventRequest r) {
        Event e = events.save(new Event(r.title(), r.venue(), r.category(), r.startsAt(), r.priceCents(), r.description()));
        List<Seat> grid = new ArrayList<>();
        for (int row = 0; row < r.rows(); row++)
            for (int n = 1; n <= r.seatsPerRow(); n++)
                grid.add(new Seat(e, String.valueOf((char) ('A' + row)), n));
        seats.saveAll(grid);
        return toDto(e);
    }

    private EventDto toDto(Event e) {
        String imageUrl = e.getImageUpdatedAt() == null ? null
                : "/api/events/" + e.getId() + "/image?v=" + e.getImageUpdatedAt().toEpochMilli();
        return new EventDto(e.getId(), e.getTitle(), e.getVenue(), e.getCategory(), e.getStartsAt(), e.getPriceCents(),
                seats.countByEventId(e.getId()), seats.countByEventIdAndStatus(e.getId(), Seat.Status.AVAILABLE),
                e.getDescription(), imageUrl);
    }
}
