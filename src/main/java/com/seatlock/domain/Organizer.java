package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** An outside organizer's public profile (shown on their events) and where it is in review. */
@Entity
@Table(name = "organizers")
public class Organizer {
    public enum Status { PENDING, APPROVED, REJECTED, SUSPENDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false, length = 600)
    private String about;
    @Column(nullable = false, length = 60)
    private String city;
    @Column(nullable = false, length = 120)
    private String contactEmail;
    @Column(nullable = false, length = 20)
    private String contactPhone;
    @Column(length = 200)
    private String website;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(length = 300)
    private String reviewNote;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    private Instant reviewedAt;

    protected Organizer() {}

    public Organizer(Long userId) { this.userId = userId; }

    public void details(String name, String about, String city, String contactEmail, String contactPhone, String website) {
        this.name = name;
        this.about = about;
        this.city = city;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.website = website;
    }

    /** A rejected application that was edited goes back into the queue. */
    public void resubmit() {
        if (status == Status.REJECTED) {
            status = Status.PENDING;
            reviewNote = null;
        }
    }

    public void approve() { review(Status.APPROVED, null); }
    public void reject(String note) { review(Status.REJECTED, note); }
    public void suspend(String note) { review(Status.SUSPENDED, note); }

    private void review(Status to, String note) {
        this.status = to;
        this.reviewNote = note;
        this.reviewedAt = Instant.now();
    }

    public boolean isApproved() { return status == Status.APPROVED; }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getAbout() { return about; }
    public String getCity() { return city; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public String getWebsite() { return website; }
    public Status getStatus() { return status; }
    public String getReviewNote() { return reviewNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
}
