package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One customer waiting for a sold-out event. FIFO by createdAt; each entry gets at most one seat offer. */
@Entity
@Table(name = "waitlist_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"}))
public class WaitlistEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id")
    private Event event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private AppUser user;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    private Long offeredSeatId;
    @Column(length = 8)
    private String offeredSeatLabel;
    private Instant offerExpiresAt;

    protected WaitlistEntry() {}

    public WaitlistEntry(Event event, AppUser user) {
        this.event = event;
        this.user = user;
    }

    public void offer(Seat seat, Instant expiresAt) {
        this.offeredSeatId = seat.getId();
        this.offeredSeatLabel = seat.label();
        this.offerExpiresAt = expiresAt;
    }

    public boolean isOffered() { return offeredSeatId != null; }
    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public AppUser getUser() { return user; }
    public Instant getCreatedAt() { return createdAt; }
    public String getOfferedSeatLabel() { return offeredSeatLabel; }
    public Instant getOfferExpiresAt() { return offerExpiresAt; }
}
