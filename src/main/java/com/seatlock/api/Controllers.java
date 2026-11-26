package com.seatlock.api;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class Controllers {
    private Controllers() {}

    @RestController
    @RequestMapping("/api/auth")
    static class AuthController {
        private final UserRepository users;
        private final PasswordEncoder encoder;
        private final JwtEncoder jwt;

        AuthController(UserRepository users, PasswordEncoder encoder, JwtEncoder jwt) {
            this.users = users;
            this.encoder = encoder;
            this.jwt = jwt;
        }

        @PostMapping("/register")
        @ResponseStatus(HttpStatus.CREATED)
        AuthResponse register(@Valid @RequestBody RegisterRequest r) {
            String email = r.email().trim().toLowerCase(Locale.ROOT);
            if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
            AppUser u = users.save(new AppUser(email, r.name().trim(), encoder.encode(r.password()), AppUser.Role.USER));
            return new AuthResponse(token(u), UserDto.of(u));
        }

        @PostMapping("/login")
        AuthResponse login(@Valid @RequestBody LoginRequest r) {
            AppUser u = users.findByEmail(r.email().trim().toLowerCase(Locale.ROOT))
                    .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
            return new AuthResponse(token(u), UserDto.of(u));
        }

        private String token(AppUser u) {
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer("seatlock").subject(u.getId().toString())
                    .claim("email", u.getEmail()).claim("name", u.getName()).claim("roles", List.of(u.getRole().name()))
                    .issuedAt(now).expiresAt(now.plus(Duration.ofHours(12))).build();
            return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        }
    }

    @RestController
    @RequestMapping("/api/events")
    static class EventController {
        private final EventService events;

        EventController(EventService events) { this.events = events; }

        @GetMapping
        List<EventDto> list() { return events.list(); }

        @GetMapping("/{id}")
        EventDto get(@PathVariable Long id) { return events.get(id); }

        @GetMapping("/{id}/seats")
        List<SeatDto> seats(@PathVariable Long id) { return events.seats(id); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        EventDto create(@Valid @RequestBody CreateEventRequest r) { return events.create(r); }
    }

    @RestController
    @RequestMapping("/api/bookings")
    static class BookingController {
        private final BookingService bookings;

        BookingController(BookingService bookings) { this.bookings = bookings; }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        BookingDto book(@AuthenticationPrincipal Jwt user, @Valid @RequestBody BookingRequest r,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
            if (key != null && key.length() > 100) throw new IllegalArgumentException("Idempotency-Key too long");
            return bookings.book(Long.valueOf(user.getSubject()), r.eventId(), r.seatIds(), key);
        }

        @GetMapping("/me")
        List<BookingDto> mine(@AuthenticationPrincipal Jwt user) { return bookings.forUser(Long.valueOf(user.getSubject())); }
    }
}
