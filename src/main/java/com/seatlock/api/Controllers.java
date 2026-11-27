package com.seatlock.api;

import com.seatlock.api.Dtos.*;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.AuthService;
import com.seatlock.service.BookingService;
import com.seatlock.service.PaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class Controllers {
    private Controllers() {}

    /**
     * Login, register and refresh return a short-lived access token in the body and set the refresh token as an
     * httpOnly, SameSite=Lax cookie (JavaScript can't read it, other sites' forms can't send it).
     */
    @RestController
    @RequestMapping("/api/auth")
    static class AuthController {
        static final String COOKIE = com.seatlock.config.SecurityConfig.REFRESH_COOKIE;
        static final String ACCESS_COOKIE = com.seatlock.config.SecurityConfig.ACCESS_COOKIE;

        private final UserRepository users;
        private final PasswordEncoder encoder;
        private final AuthService auth;
        private final boolean secureCookie;

        AuthController(UserRepository users, PasswordEncoder encoder, AuthService auth,
                       @org.springframework.beans.factory.annotation.Value("${app.auth.cookie-secure}") boolean secureCookie) {
            this.users = users;
            this.encoder = encoder;
            this.auth = auth;
            this.secureCookie = secureCookie;
        }

        @PostMapping("/register")
        ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest r) {
            String email = r.email().trim().toLowerCase(Locale.ROOT);
            if (users.existsByEmail(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
            AppUser u = users.save(new AppUser(email, r.name().trim(), encoder.encode(r.password()), AppUser.Role.USER));
            return withSession(HttpStatus.CREATED, auth.start(u));
        }

        @PostMapping("/login")
        ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest r) {
            AppUser u = users.findByEmail(r.email().trim().toLowerCase(Locale.ROOT))
                    .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
            return withSession(HttpStatus.OK, auth.start(u));
        }

        /** New access + refresh cookies from the refresh cookie (rotation). 401 + cleared cookies when the session is over. */
        @PostMapping("/refresh")
        ResponseEntity<AuthResponse> refresh(@CookieValue(name = COOKIE, required = false) String refresh) {
            try {
                return withSession(HttpStatus.OK, auth.rotate(refresh));
            } catch (AuthService.InvalidRefreshException e) {
                boolean race = e.getMessage().contains("another tab"); // keep the cookies: the other tab already replaced them
                var res = ResponseEntity.status(HttpStatus.UNAUTHORIZED);
                if (!race) res.header(HttpHeaders.SET_COOKIE, cookie(COOKIE, "", Duration.ZERO).toString(),
                        cookie(ACCESS_COOKIE, "", Duration.ZERO).toString());
                return res.build();
            }
        }

        /** Who is signed in, from the cookie alone and without rotating it. Used by the frontend route guard. */
        @GetMapping("/session")
        ResponseEntity<UserDto> session(@CookieValue(name = COOKIE, required = false) String refresh) {
            return auth.peek(refresh).map(u -> ResponseEntity.ok(UserDto.of(u)))
                    .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        @PostMapping("/logout")
        ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refresh) {
            auth.logout(refresh);
            return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie(COOKIE, "", Duration.ZERO).toString(),
                    cookie(ACCESS_COOKIE, "", Duration.ZERO).toString()).build();
        }

        /**
         * Both tokens go out as httpOnly cookies; the browser app never handles a token. The access token is also
         * in the body for non-browser clients (Swagger "Authorize", k6, scripts) that send it as a Bearer header.
         */
        private ResponseEntity<AuthResponse> withSession(HttpStatus status, AuthService.Session s) {
            return ResponseEntity.status(status)
                    .header(HttpHeaders.SET_COOKIE, cookie(COOKIE, s.refreshToken(), auth.refreshTtl()).toString(),
                            cookie(ACCESS_COOKIE, s.accessToken(), auth.accessTtl()).toString())
                    .body(new AuthResponse(s.accessToken(), UserDto.of(s.user())));
        }

        private ResponseCookie cookie(String name, String value, Duration maxAge) {
            // httpOnly: invisible to JavaScript. SameSite=Lax: not sent on cross-site POST/DELETE.
            // Path=/ so the frontend's route guard (same site via the /api proxy) sees them on page requests too.
            return ResponseCookie.from(name, value).httpOnly(true).secure(secureCookie).sameSite("Lax")
                    .path("/").maxAge(maxAge).build();
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
    @RequestMapping("/api/payments")
    static class PaymentController {
        private final PaymentService payments;

        PaymentController(PaymentService payments) { this.payments = payments; }

        /** Start paying for held seats. Returns a booking straight away when payments are off or the order is free. */
        @PostMapping("/checkout")
        CheckoutResponse checkout(@AuthenticationPrincipal Jwt user, @Valid @RequestBody BookingRequest r,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key) {
            if (key != null && key.length() > 100) throw new IllegalArgumentException("Idempotency-Key too long");
            return payments.checkout(Long.valueOf(user.getSubject()), r.eventId(), r.seatIds(), key);
        }

        @PostMapping("/verify")
        BookingDto verify(@AuthenticationPrincipal Jwt user, @Valid @RequestBody VerifyPaymentRequest r) {
            return payments.verify(Long.valueOf(user.getSubject()), r.orderId(), r.paymentId(), r.signature());
        }

        /** Razorpay -> us. Public: trust comes from the HMAC signature over the raw body. */
        @PostMapping("/webhook")
        void webhook(@RequestBody String body, @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
            payments.webhook(body, signature);
        }
    }

    @RestController
    @RequestMapping("/api/bookings")
    static class BookingController {
        private final BookingService bookings;
        private final PaymentService payments;

        BookingController(BookingService bookings, PaymentService payments) {
            this.bookings = bookings;
            this.payments = payments;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        BookingDto book(@AuthenticationPrincipal Jwt user, @Valid @RequestBody BookingRequest r,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
            if (key != null && key.length() > 100) throw new IllegalArgumentException("Idempotency-Key too long");
            // With payments on, seats are only booked after a verified payment (/api/payments/checkout).
            if (payments.enabled()) throw new IllegalArgumentException("Payment is required: use /api/payments/checkout");
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
        private final AuthService auth;

        AccountController(UserRepository users, PasswordEncoder encoder, AuthService auth) {
            this.users = users;
            this.encoder = encoder;
            this.auth = auth;
        }

        @GetMapping("/me")
        UserDto me(@AuthenticationPrincipal Jwt user) {
            return UserDto.of(users.findById(Long.valueOf(user.getSubject())).orElseThrow());
        }

        /**
         * Needs the current password. Signs out every other device (their refresh tokens are revoked);
         * their access tokens lapse within the access-token lifetime (15 min by default).
         */
        @PostMapping("/password")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void changePassword(@AuthenticationPrincipal Jwt user, @Valid @RequestBody ChangePasswordRequest r,
                            @CookieValue(name = AuthController.COOKIE, required = false) String refresh) {
            AppUser u = users.findById(Long.valueOf(user.getSubject())).orElseThrow();
            if (!encoder.matches(r.currentPassword(), u.getPasswordHash()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your current password is incorrect");
            if (r.currentPassword().equals(r.newPassword()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a password you haven't used here before");
            u.changePasswordHash(encoder.encode(r.newPassword()));
            users.save(u);
            auth.endOtherSessions(u.getId(), refresh);
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
