package com.seatlock.service;

import com.seatlock.api.Dtos.WaitlistItem;
import com.seatlock.api.Dtos.WaitlistStatus;
import com.seatlock.domain.*;
import com.seatlock.domain.Repositories.*;
import com.seatlock.service.BookingService.SeatUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Waitlist for sold-out events. When seats free up (a cancellation), the RabbitMQ consumer calls
 * {@link #offer}: each freed seat is held in Redis for the next person in line, FIFO, before anyone
 * else is told the seat changed. Each entry gets one offer; if they don't book in time the hold
 * expires and the seat goes back on general sale.
 */
@Service
public class WaitlistService {
    public static class SeatsStillAvailableException extends RuntimeException {
        public SeatsStillAvailableException() { super("Seats are still available. Pick one on the seat map."); }
    }

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistRepository waitlist;
    private final EventRepository events;
    private final UserRepository users;
    private final SeatRepository seats;
    private final SeatHoldService holds;

    public WaitlistService(WaitlistRepository waitlist, EventRepository events, UserRepository users,
                           SeatRepository seats, SeatHoldService holds) {
        this.waitlist = waitlist;
        this.events = events;
        this.users = users;
        this.seats = seats;
        this.holds = holds;
    }

    @Transactional
    public WaitlistStatus join(Long userId, Long eventId) {
        Event event = events.live(eventId);
        var existing = waitlist.findByEventIdAndUserId(eventId, userId);
        if (existing.isPresent()) return status(existing.get());
        if (!event.getStartsAt().isAfter(Instant.now())) throw new IllegalArgumentException("This event has already started");
        if (seats.countByEventIdAndStatus(eventId, Seat.Status.AVAILABLE) > 0)
            throw new SeatsStillAvailableException();
        AppUser user = users.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found"));
        return status(waitlist.saveAndFlush(new WaitlistEntry(event, user)));
    }

    @Transactional
    public void leave(Long userId, Long eventId) {
        waitlist.findByEventIdAndUserId(eventId, userId).filter(e -> !e.isOffered()).ifPresent(waitlist::delete);
    }

    @Transactional(readOnly = true)
    public WaitlistStatus statusFor(Long userId, Long eventId) {
        return waitlist.findByEventIdAndUserId(eventId, userId).map(this::status)
                .orElse(new WaitlistStatus(false, null, null, null));
    }

    @Transactional(readOnly = true)
    public List<WaitlistItem> mine(Long userId) {
        return waitlist.findByUserIdOrderByCreatedAtDesc(userId).stream().map(e -> {
            WaitlistStatus s = status(e);
            return new WaitlistItem(e.getEvent().getId(), e.getEvent().getTitle(), e.getEvent().getStartsAt(),
                    s.position(), s.offeredSeat(), s.offerExpiresAt());
        }).toList();
    }

    /** One seat held for one person in line; the caller emails them after the transaction commits. */
    public record Offer(String email, String eventTitle, Long eventId, String seat, Instant expiresAt) {}

    /** Offers freed seats to the people at the front of the line. */
    @Transactional
    public List<Offer> offer(Long eventId, List<Long> seatIds) {
        List<Offer> offers = new ArrayList<>();
        for (Long seatId : seatIds) {
            var next = waitlist.findFirstByEventIdAndOfferedSeatIdIsNullOrderByIdAsc(eventId);
            if (next.isEmpty()) break;
            WaitlistEntry entry = next.get();
            Seat seat = seats.findById(seatId).orElse(null);
            if (seat == null || seat.getStatus() != Seat.Status.AVAILABLE) continue;
            try {
                var hold = holds.hold(entry.getUser().getId(), eventId, List.of(seatId));
                entry.offer(seat, hold.expiresAt());
                offers.add(new Offer(entry.getUser().getEmail(), entry.getEvent().getTitle(), eventId, seat.label(), hold.expiresAt()));
                log.info("Waitlist: seat {} held for {} until {}", seat.label(), entry.getUser().getEmail(), hold.expiresAt());
            } catch (SeatUnavailableException | IllegalArgumentException e) {
                // Someone grabbed it first, or this person already holds the max; try the next seat.
            }
        }
        return offers;
    }

    private WaitlistStatus status(WaitlistEntry e) {
        if (e.isOffered()) return new WaitlistStatus(true, null, e.getOfferedSeatLabel(), e.getOfferExpiresAt());
        int position = (int) waitlist.countByEventIdAndOfferedSeatIdIsNullAndIdLessThanEqual(e.getEvent().getId(), e.getId());
        return new WaitlistStatus(true, position, null, null);
    }
}
