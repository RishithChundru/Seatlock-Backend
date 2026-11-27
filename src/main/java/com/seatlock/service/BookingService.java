package com.seatlock.service;

import com.seatlock.api.Dtos.BookingDto;
import com.seatlock.api.Dtos.CheckInResult;
import com.seatlock.api.Dtos.SharedTicket;
import com.seatlock.api.Dtos.Insights;
import com.seatlock.api.Dtos.RecentBooking;
import com.seatlock.api.Dtos.WeekPoint;
import java.util.Locale;
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
    public record BookingCancelled(Long bookingId, Long eventId, List<Long> seatIds,
                                   String email, String eventTitle, List<String> seatLabels, int refundCents,
                                   boolean eventCancelled) {}

    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final UserRepository users;
    private final SeatHoldService holds;
    private final WaitlistRepository waitlist;
    private final ApplicationEventPublisher publisher;
    private final com.seatlock.config.SeatlockMetrics metrics;
    private final Access access;

    public BookingService(BookingRepository bookings, SeatRepository seats, EventRepository events, UserRepository users,
                          SeatHoldService holds, WaitlistRepository waitlist, ApplicationEventPublisher publisher,
                          com.seatlock.config.SeatlockMetrics metrics, Access access) {
        this.access = access;
        this.metrics = metrics;
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

        Event event = events.live(eventId);
        AppUser user = users.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found"));
        List<Seat> selected = seats.findAllById(seatIds);
        if (selected.size() != seatIds.size() || selected.stream().anyMatch(s -> !s.getEvent().getId().equals(eventId)))
            throw new IllegalArgumentException("Seats do not belong to this event");
        if (selected.stream().anyMatch(s -> s.getStatus() != Seat.Status.AVAILABLE))
            throw new SeatUnavailableException();

        holds.assertBookable(userId, seatIds);

        int total = selected.stream().mapToInt(Seat::priceCents).sum(); // tiers: VIP/Premium/Standard
        Booking booking = bookings.save(new Booking(user, event, total, idempotencyKey));
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
        if (!booking.cancellableUntil().isAfter(Instant.now()))
            throw new IllegalArgumentException("Cancellations close 3 days before the event. You can share your ticket with someone instead.");

        List<Seat> freed = booking.cancel();
        seats.saveAllAndFlush(freed);
        publisher.publishEvent(new BookingCancelled(booking.getId(), booking.getEvent().getId(), freed.stream().map(Seat::getId).toList(),
                booking.getUser().getEmail(), booking.getEvent().getTitle(), booking.seatLabels(), booking.getTotalCents(), false));
        return BookingDto.of(booking);
    }

    /**
     * The organiser called the event off: every confirmed booking is cancelled in the caller's transaction.
     * After commit each holder is refunded (if they paid) and emailed; freed seats don't go to the waitlist.
     */
    @Transactional
    public int cancelAllForEvent(Long eventId) {
        List<Booking> confirmed = bookings.findByEventIdAndStatus(eventId, Booking.Status.CONFIRMED);
        for (Booking b : confirmed) {
            List<Seat> freed = b.cancel();
            seats.saveAll(freed);
            publisher.publishEvent(new BookingCancelled(b.getId(), eventId, freed.stream().map(Seat::getId).toList(),
                    b.getUser().getEmail(), b.getEvent().getTitle(), b.seatLabels(), b.getTotalCents(), true));
        }
        return confirmed.size();
    }

    /** Check-in from a scanner: organizers and their staff can only admit tickets for their own events. */
    @Transactional
    public CheckInResult checkIn(String rawCode, Long eventId, Long scannerUserId) {
        Booking b = bookings.findByTicketCode(rawCode.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (b != null && !access.canScan(scannerUserId, b.getEvent())) {
            metrics.checkIn("refused");
            throw new CheckInRefusedException("This ticket isn't for one of your events");
        }
        return checkIn(rawCode, eventId);
    }

    /**
     * Gate check-in by ticket code. The conditional UPDATE makes double admission impossible even with
     * several scanners; a second scan reports when the ticket was first used.
     */
    @Transactional
    public CheckInResult checkIn(String rawCode, Long eventId) {
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        Booking b = bookings.findByTicketCode(code).orElse(null);
        if (b == null) {
            metrics.checkIn("unknown");
            throw new NoSuchElementException("No ticket with code " + code);
        }
        if (eventId != null && !b.getEvent().getId().equals(eventId)) {
            metrics.checkIn("refused");
            throw new CheckInRefusedException("This ticket is for " + b.getEvent().getTitle());
        }
        if (b.isCancelled()) {
            metrics.checkIn("refused");
            throw new CheckInRefusedException("This booking was cancelled");
        }
        boolean admitted = bookings.checkIn(b.getId(), Instant.now()) == 1;
        metrics.checkIn(admitted ? "admitted" : "repeat");
        Booking fresh = bookings.findById(b.getId()).orElseThrow();
        return new CheckInResult(admitted ? "ADMITTED" : "ALREADY_CHECKED_IN", fresh.getId(), fresh.getEvent().getTitle(),
                fresh.getUser().getName(), fresh.seatLabels(), fresh.getCheckedInAt());
    }

    public static class CheckInRefusedException extends RuntimeException {
        public CheckInRefusedException(String message) { super(message); }
    }

    /** Owner only. Returns the (stable) token for the public ticket page. */
    @Transactional
    public String share(Long userId, Long bookingId) {
        Booking b = bookings.findById(bookingId).filter(x -> x.getUser().getId().equals(userId))
                .orElseThrow(() -> new NoSuchElementException("Booking not found"));
        if (b.isCancelled()) throw new IllegalArgumentException("This booking was cancelled");
        if (b.getCheckedInAt() != null) throw new IllegalArgumentException("This ticket has already been used at the gate");
        return b.shareToken();
    }

    /** Public: anyone holding the link sees the ticket (that's the point: it can be handed to a friend). */
    @Transactional(readOnly = true)
    public SharedTicket sharedTicket(String token) {
        Booking b = bookings.findByShareToken(token).orElseThrow(() -> new NoSuchElementException("Ticket not found"));
        Event e = b.getEvent();
        String firstName = b.getUser().getName().trim().split("\\s+")[0];
        return new SharedTicket(e.getId(), e.getTitle(), e.getVenue(), e.getStartsAt(), b.seatLabels(),
                b.isCancelled() || b.getCheckedInAt() != null ? null : b.getTicketCode(), // used or void: nothing to scan
                b.getStatus().name(), b.getCheckedInAt(), firstName);
    }

    static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
    static final int WEEKS = 8;

    /** Sales per week (last 8, oldest first) and the 6 latest bookings, for the events a dashboard user may see. */
    @Transactional(readOnly = true)
    public Insights insights(List<Event> visible) {
        java.time.LocalDate thisWeek = java.time.LocalDate.now(IST).with(java.time.DayOfWeek.MONDAY);
        java.time.LocalDate first = thisWeek.minusWeeks(WEEKS - 1);
        long[] count = new long[WEEKS];
        long[] revenue = new long[WEEKS];
        List<Long> ids = visible.stream().map(Event::getId).toList();
        List<RecentBooking> recent = List.of();
        if (!ids.isEmpty()) {
            for (Object[] row : bookings.salesSince(ids, first.atStartOfDay(IST).toInstant())) {
                java.time.LocalDate day = ((Instant) row[0]).atZone(IST).toLocalDate();
                int week = (int) java.time.temporal.ChronoUnit.WEEKS.between(first, day.with(java.time.DayOfWeek.MONDAY));
                if (week < 0 || week >= WEEKS) continue;
                count[week]++;
                revenue[week] += ((Number) row[1]).longValue();
            }
            recent = bookings.latestFor(ids, org.springframework.data.domain.PageRequest.of(0, 6)).stream()
                    .map(b -> new RecentBooking(b.getId(), b.getEvent().getId(), b.getEvent().getTitle(), b.seatLabels(),
                            b.getUser().getName(), b.getTotalCents(), b.getStatus().name(), b.getCreatedAt(), b.getCheckedInAt() != null))
                    .toList();
        }
        List<WeekPoint> weeks = new java.util.ArrayList<>();
        for (int i = 0; i < WEEKS; i++) weeks.add(new WeekPoint(first.plusWeeks(i), count[i], revenue[i]));
        return new Insights(weeks, recent);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> forUser(Long userId) {
        return bookings.findByUserIdOrderByCreatedAtDesc(userId).stream().map(BookingDto::of).toList();
    }
}
