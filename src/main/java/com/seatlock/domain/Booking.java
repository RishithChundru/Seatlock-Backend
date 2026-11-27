package com.seatlock.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private AppUser user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id")
    private Event event;
    @Column(nullable = false)
    private int totalCents;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
    @Column(length = 20)
    private String ticketCode;
    private Instant ticketIssuedAt;
    // Default fills existing rows when Hibernate adds the column to a live table.
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @ColumnDefault("'CONFIRMED'")
    private Status status = Status.CONFIRMED;
    private Instant cancelledAt;
    private Instant checkedInAt;
    /** "A1,A2": kept so a cancelled booking still shows its seats after they are released. */
    @Column(length = 400)
    private String seatLabels;
    @Column(length = 32)
    private String shareToken;
    @OneToMany(mappedBy = "booking")
    @OrderBy("rowLabel, seatNumber")
    private List<Seat> seats = new ArrayList<>();

    protected Booking() {}

    public Booking(AppUser user, Event event, int totalCents, String idempotencyKey) {
        this.user = user;
        this.event = event;
        this.totalCents = totalCents;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public Event getEvent() { return event; }
    public int getTotalCents() { return totalCents; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Seat> getSeats() { return seats; }
    public String getTicketCode() { return ticketCode; }
    public boolean isTicketIssued() { return ticketIssuedAt != null; }

    public void issueTicket(String code) {
        this.ticketCode = code;
        this.ticketIssuedAt = Instant.now();
    }

    public enum Status { CONFIRMED, CANCELLED }

    /** Customers can cancel until this long before the event; after that they can only share the ticket. */
    public static final Duration CANCEL_CUTOFF = Duration.ofDays(3);

    public Instant cancellableUntil() { return event.getStartsAt().minus(CANCEL_CUTOFF); }

    /** The token for the public ticket page, created on first share. */
    public String shareToken() {
        if (shareToken == null) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            shareToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return shareToken;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public Status getStatus() { return status; }
    public boolean isCancelled() { return status == Status.CANCELLED; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCheckedInAt() { return checkedInAt; }

    public List<String> seatLabels() {
        if (seatLabels != null) return List.of(seatLabels.split(","));
        return seats.stream().map(Seat::label).toList();
    }

    public void recordSeatLabels() {
        this.seatLabels = String.join(",", seats.stream().map(Seat::label).toList());
    }

    /** Marks the booking cancelled and frees its seats (the caller saves the seats). */
    public List<Seat> cancel() {
        if (seatLabels == null) recordSeatLabels();
        this.status = Status.CANCELLED;
        this.cancelledAt = Instant.now();
        List<Seat> freed = new ArrayList<>(seats);
        freed.forEach(Seat::release);
        seats.clear();
        return freed;
    }
}
