package com.seatlock.domain;

import jakarta.persistence.*;
import java.time.Instant;
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
}
