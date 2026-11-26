package com.seatlock.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class AppUser {
    public enum Role { USER, ADMIN }

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
}
