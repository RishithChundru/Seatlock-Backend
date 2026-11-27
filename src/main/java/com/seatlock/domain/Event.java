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
    /** Standard-tier price. */
    @Column(nullable = false)
    private int priceCents;
    /** Null when the event has no seats in that tier (and for events created before tiers existed). */
    private Integer vipPriceCents;
    private Integer premiumPriceCents;
    @Column(length = 1000)
    private String description;
    private Instant imageUpdatedAt;
    /** Object-storage key when the cover lives in S3/R2; null when it's in Postgres (or there's none). */
    @Column(length = 200)
    private String imageKey;
    /** Null = hosted by Seatlock (created by the super admin). */
    private Long organizerId;
    /** Set when the organizer or admin calls the event off; the row stays so bookings keep their history. */
    private Instant cancelledAt;

    protected Event() {}

    public boolean isCancelled() { return cancelledAt != null; }
    public void cancel() { if (cancelledAt == null) cancelledAt = Instant.now(); }

    public Event(String title, String venue, String category, Instant startsAt, int priceCents, String description) {
        this.title = title;
        this.venue = venue;
        this.category = category;
        this.startsAt = startsAt;
        this.priceCents = priceCents;
        this.description = description;
    }

    public void setTierPrices(Integer vipPriceCents, Integer premiumPriceCents) {
        this.vipPriceCents = vipPriceCents;
        this.premiumPriceCents = premiumPriceCents;
    }

    public int priceFor(Seat.Tier tier) {
        return switch (tier) {
            case VIP -> vipPriceCents != null ? vipPriceCents : priceCents;
            case PREMIUM -> premiumPriceCents != null ? premiumPriceCents : priceCents;
            case STANDARD -> priceCents;
        };
    }

    /** "Skyline Arena, Hyderabad" -> "Hyderabad". Venues are entered as "Place, City". */
    public String city() {
        int comma = venue.lastIndexOf(',');
        return comma < 0 ? venue.trim() : venue.substring(comma + 1).trim();
    }

    public String getDescription() { return description; }
    public Instant getImageUpdatedAt() { return imageUpdatedAt; }
    public void imageChanged() { this.imageUpdatedAt = Instant.now(); }
    public void imageRemoved() { this.imageUpdatedAt = null; this.imageKey = null; }
    public String getImageKey() { return imageKey; }
    public Long getOrganizerId() { return organizerId; }
    public void setOrganizerId(Long organizerId) { this.organizerId = organizerId; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getVenue() { return venue; }
    public String getCategory() { return category; }
    public Instant getStartsAt() { return startsAt; }
    public int getPriceCents() { return priceCents; }
    public Integer getVipPriceCents() { return vipPriceCents; }
    public Integer getPremiumPriceCents() { return premiumPriceCents; }
}
