package com.seatlock.config;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Creates the super admin account when there is none, from ADMIN_EMAIL and ADMIN_PASSWORD (no credentials live in
 * the code). No sample data: events come from the super admin and organizers.
 */
@Component
class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    DataSeeder(UserRepository users, PasswordEncoder encoder,
               @Value("${app.seed.admin-email}") String adminEmail, @Value("${app.seed.admin-password}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (!users.findAllByRole(AppUser.Role.ADMIN).isEmpty()) return;
        boolean generated = adminPassword == null || adminPassword.isBlank();
        String password = generated ? randomPassword() : adminPassword;
        if (generated) {
            // Local convenience only; production always sets ADMIN_PASSWORD (render.yaml prompts for it).
            log.warn("ADMIN_PASSWORD is not set. Generated admin password for {}: {} (set ADMIN_PASSWORD to choose your own)", adminEmail, password);
        } else if (password.length() < 8) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 8 characters");
        }
        if (users.findByEmail(adminEmail).isEmpty())
            users.save(new AppUser(adminEmail, "Admin", encoder.encode(password), AppUser.Role.ADMIN));
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
