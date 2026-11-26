package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One refresh token (hash only). All rotations of one login share a familyId. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private AppUser user;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false)
    private UUID familyId;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant revokedAt;

    protected RefreshToken() {}

    public RefreshToken(AppUser user, String tokenHash, UUID familyId, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public void revoke() { if (revokedAt == null) revokedAt = Instant.now(); }
    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired() { return !expiresAt.isAfter(Instant.now()); }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public UUID getFamilyId() { return familyId; }
    public Instant getRevokedAt() { return revokedAt; }
}
