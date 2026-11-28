package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users")
public class AppUser {
    /** ADMIN is the super admin. ORGANIZER hosts their own events. STAFF scans tickets for one organizer. */
    public enum Role { USER, ADMIN, ORGANIZER, STAFF }

    /** Password hash of an account that can't sign in: a staff invite not yet accepted, or removed staff. */
    public static final String NO_PASSWORD = "!";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String email;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Role role;
    /** For STAFF: the organizer they work for. */
    private Long staffOf;
    /** Set while the user has a profile photo (also busts the image cache). */
    private Instant avatarUpdatedAt;

    protected AppUser() {}

    public AppUser(String email, String name, String passwordHash, Role role) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }

    public void changePasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Long getStaffOf() { return staffOf; }
    public boolean hasPassword() { return !NO_PASSWORD.equals(passwordHash); }
    public void setRole(Role role) { this.role = role; }
    public void setStaffOf(Long organizerId) { this.staffOf = organizerId; }
    public Instant getAvatarUpdatedAt() { return avatarUpdatedAt; }
    public void setAvatarUpdatedAt(Instant at) { this.avatarUpdatedAt = at; }
}
