package com.seatlock.api;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventSearch;
import com.seatlock.service.EventService;
import com.seatlock.realtime.SeatUpdates;
import com.seatlock.service.SeatHoldService;
import com.seatlock.service.WaitlistService;
import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.seatlock.domain.EventImage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
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
        private final EventSearch search;
        private final SeatUpdates updates;
        private final WaitlistService waitlist;

        EventController(EventService events, EventSearch search, SeatUpdates updates, WaitlistService waitlist) {
            this.events = events;
            this.search = search;
            this.updates = updates;
            this.waitlist = waitlist;
        }

        @GetMapping
        List<EventDto> list() { return events.list(); }

        /** Upcoming events, filtered and paginated: ?q=&category=&city=&from=&to=&maxPrice=(paise)&page=&size= */
        @GetMapping("/search")
        EventPage search(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                         @RequestParam(required = false) String city, @RequestParam(required = false) Instant from,
                         @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer maxPrice,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
            if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("page must be >= 0 and size 1-50");
            return search.search(q, category, city, from, to, maxPrice, page, size);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void delete(@PathVariable Long id) { events.delete(id); }

        @DeleteMapping("/{id}/image")
        EventDto removeImage(@PathVariable Long id) { return events.removeImage(id); }

        @GetMapping("/{id}")
        EventDto get(@PathVariable Long id) { return events.get(id); }

        @GetMapping("/{id}/seats")
        List<SeatDto> seats(@PathVariable Long id, @AuthenticationPrincipal Jwt user) {
            return events.seats(id, user == null ? null : Long.valueOf(user.getSubject()));
        }

        /** Server-Sent Events: a "seats" event whenever this event's seat map changes. */
        @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(@PathVariable Long id) {
            events.get(id); // 404 for unknown events
            return updates.subscribe(id);
        }

        @GetMapping("/{id}/waitlist")
        WaitlistStatus waitlistStatus(@PathVariable Long id, @AuthenticationPrincipal Jwt user) {
            if (user == null) return new WaitlistStatus(false, null, null, null);
            return waitlist.statusFor(Long.valueOf(user.getSubject()), id);
        }

        @PostMapping("/{id}/waitlist")
        WaitlistStatus joinWaitlist(@PathVariable Long id, @AuthenticationPrincipal Jwt user) {
            return waitlist.join(Long.valueOf(user.getSubject()), id);
        }

        @DeleteMapping("/{id}/waitlist")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void leaveWaitlist(@PathVariable Long id, @AuthenticationPrincipal Jwt user) {
            waitlist.leave(Long.valueOf(user.getSubject()), id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        EventDto create(@Valid @RequestBody CreateEventRequest r) { return events.create(r); }

        @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        EventDto uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
            return events.setImage(id, file.getBytes());
        }

        @GetMapping("/{id}/image")
        ResponseEntity<byte[]> image(@PathVariable Long id) {
            EventImage img = events.image(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(img.getContentType()))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()) // URL is versioned
                    .body(img.getData());
        }
    }

    @RestController
    @RequestMapping("/api/holds")
    static class HoldController {
        private final SeatHoldService holds;

        HoldController(SeatHoldService holds) { this.holds = holds; }

        @PostMapping
        HoldResponse hold(@AuthenticationPrincipal Jwt user, @Valid @RequestBody HoldRequest r) {
            return holds.hold(Long.valueOf(user.getSubject()), r.eventId(), r.seatIds());
        }

        @DeleteMapping("/{seatId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void release(@AuthenticationPrincipal Jwt user, @PathVariable Long seatId) {
            holds.releaseAndNotify(Long.valueOf(user.getSubject()), seatId);
        }
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

        @PostMapping("/{id}/cancel")
        BookingDto cancel(@AuthenticationPrincipal Jwt user, @PathVariable Long id) {
            return bookings.cancel(Long.valueOf(user.getSubject()), id);
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class AdminController {
        private final BookingService bookings;

        AdminController(BookingService bookings) { this.bookings = bookings; }

        /** Gate check-in: scan or type the ticket code. ADMIN only (see SecurityConfig). */
        @PostMapping("/checkin")
        CheckInResult checkIn(@Valid @RequestBody CheckInRequest r) { return bookings.checkIn(r.code(), r.eventId()); }
    }

    @RestController
    @RequestMapping("/api/account")
    static class AccountController {
        private final UserRepository users;
        private final PasswordEncoder encoder;

        AccountController(UserRepository users, PasswordEncoder encoder) {
            this.users = users;
            this.encoder = encoder;
        }

        @GetMapping("/me")
        UserDto me(@AuthenticationPrincipal Jwt user) {
            return UserDto.of(users.findById(Long.valueOf(user.getSubject())).orElseThrow());
        }

        /**
         * Needs the current password. shortcut: tokens already issued stay valid until they expire (12 h);
         * add a token version claim to revoke them on password change.
         */
        @PostMapping("/password")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void changePassword(@AuthenticationPrincipal Jwt user, @Valid @RequestBody ChangePasswordRequest r) {
            AppUser u = users.findById(Long.valueOf(user.getSubject())).orElseThrow();
            if (!encoder.matches(r.currentPassword(), u.getPasswordHash()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your current password is incorrect");
            if (r.currentPassword().equals(r.newPassword()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a password you haven't used here before");
            u.changePasswordHash(encoder.encode(r.newPassword()));
            users.save(u);
        }
    }

    @RestController
    @RequestMapping("/api/waitlist")
    static class WaitlistController {
        private final WaitlistService waitlist;

        WaitlistController(WaitlistService waitlist) { this.waitlist = waitlist; }

        @GetMapping("/me")
        List<WaitlistItem> mine(@AuthenticationPrincipal Jwt user) { return waitlist.mine(Long.valueOf(user.getSubject())); }
    }
}
