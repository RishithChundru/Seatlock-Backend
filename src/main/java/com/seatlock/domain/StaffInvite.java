package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** An emailed invite to create a gate staff account for one organizer. One use; expires. */
@Entity
@Table(name = "staff_invites")
public class StaffInvite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long organizerId;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(nullable = false, length = 60)
    private String name;
    @Column(nullable = false, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant usedAt;
    private Instant declinedAt;

    protected StaffInvite() {}

    public StaffInvite(Long organizerId, String email, String name, String tokenHash, Instant expiresAt) {
        this.organizerId = organizerId;
        this.email = email;
        this.name = name;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() { return usedAt == null && declinedAt == null && expiresAt.isAfter(Instant.now()); }
    public void decline() { this.declinedAt = Instant.now(); }
    public Instant getDeclinedAt() { return declinedAt; }
    public void use() { this.usedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getOrganizerId() { return organizerId; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
}
