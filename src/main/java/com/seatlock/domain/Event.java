package com.seatlock.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String venue;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private Instant startsAt;
    @Column(nullable = false)
    private int priceCents;

    protected Event() {}

    public Event(String title, String venue, String category, Instant startsAt, int priceCents) {
        this.title = title;
        this.venue = venue;
        this.category = category;
        this.startsAt = startsAt;
        this.priceCents = priceCents;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public String getCategory() { return category; }
    public Instant getStartsAt() { return startsAt; }
    public int getPriceCents() { return priceCents; }
}
