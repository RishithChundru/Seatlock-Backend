package com.seatlock.service;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.Booking;
import com.seatlock.domain.Event;
import com.seatlock.domain.EventImage;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.SeatRows.SeatRow;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class EventService {
    public static final String LIST_CACHE = "events";
    public static final String ITEM_CACHE = "event";

    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private final EventRepository events;
    private final SeatRepository seats;
    private final SeatHoldService holds;
    private final EventImageRepository images;
    private final SeatRows seatRows;
    private final BookingRepository bookings;
    private final WaitlistRepository waitlist;

    public EventService(EventRepository events, SeatRepository seats, SeatHoldService holds, EventImageRepository images,
                        SeatRows seatRows, BookingRepository bookings, WaitlistRepository waitlist) {
        this.events = events;
        this.seats = seats;
        this.holds = holds;
        this.images = images;
        this.seatRows = seatRows;
        this.bookings = bookings;
        this.waitlist = waitlist;
    }

    // Cached reads are deliberately not @Transactional: a cache hit must not borrow a pooled DB connection.
    @Cacheable(value = LIST_CACHE, sync = true) // sync: one rebuild per instance after eviction, not a stampede
    public List<EventDto> list() {
        // shortcut: 2 count queries per event, fine for dozens of events (and cached); use one GROUP BY query when it grows
        return new ArrayList<>(events.findAllByOrderByStartsAtAsc().stream().map(this::toDto).toList());
    }

    @Cacheable(value = ITEM_CACHE, sync = true)
    public EventDto get(Long id) {
        return toDto(events.findById(id).orElseThrow(() -> new NoSuchElementException("Event not found")));
    }

    /** Sold seats come from the cached SeatRows; live holds are overlaid from Redis on every call. No DB on a hit. */
    public List<SeatDto> seats(Long eventId, Long viewerId) {
        List<SeatRow> rows = seatRows.forEvent(eventId);
        Map<Long, Long> held = holds.holders(rows.stream().map(SeatRow::id).toList());
        List<Long> mine = held.entrySet().stream().filter(h -> h.getValue().equals(viewerId)).map(Map.Entry::getKey).toList();
        Map<Long, Instant> expiries = holds.expiries(mine); // at most 10 seats per viewer
        return rows.stream().map(s -> {
            Long holder = held.get(s.id());
            String status = s.booked() ? "BOOKED" : holder != null ? "HELD" : "AVAILABLE";
            boolean isMine = !s.booked() && holder != null && holder.equals(viewerId);
            return new SeatDto(s.id(), s.row(), s.number(), status, isMine, isMine ? expiries.get(s.id()) : null,
                    s.tier().name(), s.priceCents());
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
        if (r.vip() + r.premium() > r.rows()) throw new IllegalArgumentException("VIP and Premium rows can't exceed the total rows");
        Event e = new Event(r.title(), r.venue(), r.category(), r.startsAt(), r.priceCents(), r.description());
        // Default upgrades: VIP 2x, Premium 1.5x the standard price, rounded to whole rupees.
        e.setTierPrices(r.vip() > 0 ? Objects.requireNonNullElse(r.vipPriceCents(), roundRupees(r.priceCents() * 2.0)) : null,
                r.premium() > 0 ? Objects.requireNonNullElse(r.premiumPriceCents(), roundRupees(r.priceCents() * 1.5)) : null);
        events.save(e);
        List<Seat> grid = new ArrayList<>();
        for (int row = 0; row < r.rows(); row++) {
            Seat.Tier tier = row < r.vip() ? Seat.Tier.VIP : row < r.vip() + r.premium() ? Seat.Tier.PREMIUM : Seat.Tier.STANDARD;
            for (int n = 1; n <= r.seatsPerRow(); n++)
                grid.add(new Seat(e, String.valueOf((char) ('A' + row)), n, tier));
        }
        seats.saveAll(grid);
        return toDto(e);
    }

    private static int roundRupees(double paise) { return (int) Math.round(paise / 100) * 100; }

    @Caching(evict = {@CacheEvict(value = LIST_CACHE, allEntries = true), @CacheEvict(value = ITEM_CACHE, key = "#eventId")})
    @Transactional
    public EventDto removeImage(Long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found"));
        if (images.existsById(eventId)) images.deleteById(eventId);
        e.imageRemoved();
        return toDto(e);
    }

    /** Admin delete. Refused while anyone still holds a confirmed booking: cancel those first. */
    @Caching(evict = {@CacheEvict(value = LIST_CACHE, allEntries = true), @CacheEvict(value = ITEM_CACHE, key = "#eventId"),
                      @CacheEvict(value = SeatRows.CACHE, key = "#eventId")})
    @Transactional
    public void delete(Long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found"));
        if (bookings.existsByEventIdAndStatus(eventId, Booking.Status.CONFIRMED))
            throw new EventHasBookingsException();
        waitlist.deleteByEventId(eventId);
        seats.deleteByEventId(eventId);
        bookings.deleteByEventId(eventId); // only cancelled ones are left
        if (images.existsById(eventId)) images.deleteById(eventId);
        events.delete(e);
    }

    public static class EventHasBookingsException extends RuntimeException {
        public EventHasBookingsException() { super("This event has confirmed bookings. Cancel them before deleting it."); }
    }

    private EventDto toDto(Event e) {
        String imageUrl = e.getImageUpdatedAt() == null ? null
                : "/api/events/" + e.getId() + "/image?v=" + e.getImageUpdatedAt().toEpochMilli();
        List<TierPrice> tiers = new ArrayList<>();
        if (e.getVipPriceCents() != null) tiers.add(new TierPrice("VIP", e.getVipPriceCents()));
        if (e.getPremiumPriceCents() != null) tiers.add(new TierPrice("PREMIUM", e.getPremiumPriceCents()));
        tiers.add(new TierPrice("STANDARD", e.getPriceCents()));
        return new EventDto(e.getId(), e.getTitle(), e.getVenue(), e.getCategory(), e.getStartsAt(), e.getPriceCents(),
                seats.countByEventId(e.getId()), seats.countByEventIdAndStatus(e.getId(), Seat.Status.AVAILABLE),
                e.getDescription(), imageUrl, e.city(), tiers);
    }
}
