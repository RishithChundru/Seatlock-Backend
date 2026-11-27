package com.seatlock.api;

import com.seatlock.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    /** organizerStatus: for ORGANIZER accounts, PENDING / APPROVED / REJECTED / SUSPENDED (else null). */
    public record UserDto(Long id, String email, String name, String role, String organizerStatus) {
        public static UserDto of(AppUser u) { return of(u, null); }
        public static UserDto of(AppUser u, String organizerStatus) {
            return new UserDto(u.getId(), u.getEmail(), u.getName(), u.getRole().name(), organizerStatus);
        }
    }

    /** Organizer sign-up: a separate account type from customers, created together with its application. */
    public record OrganizerSignupRequest(@NotNull @Valid RegisterRequest account, @NotNull @Valid OrganizerRequest organizer) {}

    // Serializable: cached in Redis
    /** imageUrl is versioned (?v=) so browsers can cache it forever; null when no cover was uploaded. */
    public record EventDto(Long id, String title, String venue, String category, Instant startsAt, int priceCents,
                           long totalSeats, long availableSeats, String description, String imageUrl,
                           String city, List<TierPrice> tiers, HostDto host) implements Serializable {}

    /** "Hosted by" on an event: the organizer's public profile. Null host = hosted by Seatlock. */
    public record HostDto(Long id, String name, String about, String city, String contactEmail, String contactPhone,
                          String website, String logoUrl) implements Serializable {
        public static HostDto of(Organizer o, String logoUrl) {
            return new HostDto(o.getId(), o.getName(), o.getAbout(), o.getCity(), o.getContactEmail(), o.getContactPhone(),
                    o.getWebsite(), logoUrl);
        }
    }

    /** Organizer onboarding form. Everything here is shown to customers on the organizer's events. */
    public record OrganizerRequest(@NotBlank @Size(min = 2, max = 80) String name,
                                   @NotBlank @Size(min = 20, max = 600) String about,
                                   @NotBlank @Size(max = 60) String city,
                                   @NotBlank @Email @Size(max = 120) String contactEmail,
                                   // India only for now: +91 and a 10-digit mobile number starting with 6-9 (spaces/dashes allowed).
                                   @NotBlank @Pattern(regexp = "^\\+91[\\s-]?[6-9][0-9]{4}[\\s-]?[0-9]{5}$",
                                           message = "must be an Indian mobile number: +91 and 10 digits") String contactPhone,
                                   @Size(max = 200) @Pattern(regexp = "^$|^https?://\\S+$", message = "must start with http:// or https://") String website) {}

    /** status: PENDING, APPROVED, REJECTED or SUSPENDED. reviewNote: why it was rejected or suspended. */
    public record OrganizerDto(Long id, String name, String about, String city, String contactEmail, String contactPhone,
                               String website, String status, String reviewNote, Instant createdAt, Instant reviewedAt,
                               String accountName, String accountEmail, String logoUrl) {}

    public record RejectRequest(@NotBlank @Size(max = 300) String note) {}

    public record NoteRequest(@Size(max = 300) String note) {}

    public record StaffRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record StaffDto(Long id, String name, String email) {}

    /** Dashboard row: the event plus its sales so far (confirmed bookings only). */
    public record ManagedEvent(EventDto event, long bookings, long revenueCents, long checkedIn) {}

    /** tier: VIP, PREMIUM or STANDARD. Listed most expensive first. */
    public record TierPrice(String tier, int priceCents) implements Serializable {}

    /** One page of search results plus the filter options available across all upcoming events. */
    public record EventPage(List<EventDto> items, long total, int page, int size,
                            List<String> cities, List<String> categories) {}

    /**
     * status: AVAILABLE, BOOKED, or HELD (in someone's 5-minute checkout hold). mine = held by the caller;
     * holdExpiresAt is set only on the caller's own holds so the UI can show a countdown after a reload.
     */
    public record SeatDto(Long id, String row, int number, String status, boolean mine, Instant holdExpiresAt,
                          String tier, int priceCents) {}

    /** status: CONFIRMED or CANCELLED. Cancelled bookings are refunded in full (simulated). */
    public record BookingDto(Long id, Long eventId, String eventTitle, String venue, Instant startsAt,
                             int totalCents, Instant createdAt, List<String> seats, String ticketCode,
                             String status, Instant cancelledAt, Instant checkedInAt, Instant cancellableUntil) {
        public static BookingDto of(Booking b) {
            return new BookingDto(b.getId(), b.getEvent().getId(), b.getEvent().getTitle(), b.getEvent().getVenue(),
                    b.getEvent().getStartsAt(), b.getTotalCents(), b.getCreatedAt(), b.seatLabels(), b.getTicketCode(),
                    b.getStatus().name(), b.getCancelledAt(), b.getCheckedInAt(), b.cancellableUntil());
        }
    }

    public record ShareResponse(String token) {}

    /** What anyone with the share link sees: no email, no price, no booking id. ticketCode is null once cancelled or used. */
    public record SharedTicket(Long eventId, String eventTitle, String venue, Instant startsAt, List<String> seats,
                               String ticketCode, String status, Instant checkedInAt, String sharedBy) {}

    public record CheckInRequest(@NotBlank @Size(max = 20) String code, Long eventId) {}

    /** result: ADMITTED (first scan) or ALREADY_CHECKED_IN (shows when, so staff can spot a copied ticket). */
    public record CheckInResult(String result, Long bookingId, String eventTitle, String holder, List<String> seats,
                                Instant checkedInAt) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    /** Caller's place on an event's waitlist. position counts people still waiting, 1 = next in line. */
    public record WaitlistStatus(boolean joined, Integer position, String offeredSeat, Instant offerExpiresAt) {}

    public record WaitlistItem(Long eventId, String eventTitle, Instant startsAt, Integer position,
                               String offeredSeat, Instant offerExpiresAt) {}

    /** Published to RabbitMQ after a cancellation commits; the waitlist consumer offers these seats. */
    public record SeatsReleased(Long eventId, List<Long> seatIds) {}

    public record HoldRequest(@NotNull Long eventId, @NotEmpty @Size(max = 10) List<@NotNull Long> seatIds) {}

    public record HoldResponse(List<Long> seatIds, Instant expiresAt) {}

    /** Published to RabbitMQ after a booking commits. */
    public record BookingConfirmed(Long bookingId, String email, String eventTitle, List<String> seats) {}

    /** Either booking (done: payments off, or a free order) or a Razorpay order to open in Checkout. */
    public record CheckoutResponse(BookingDto booking, String orderId, Integer amountCents, String currency, String keyId) {
        public static CheckoutResponse booked(BookingDto b) { return new CheckoutResponse(b, null, null, null, null); }
        public static CheckoutResponse order(String orderId, int amountCents, String keyId) {
            return new CheckoutResponse(null, orderId, amountCents, "INR", keyId);
        }
    }

    public record VerifyPaymentRequest(@NotBlank @Size(max = 40) String orderId, @NotBlank @Size(max = 40) String paymentId,
                                       @NotBlank @Size(max = 128) String signature) {}

    public record BookingRequest(@NotNull Long eventId, @NotEmpty @Size(max = 10) List<@NotNull Long> seatIds) {}

    public record CreateEventRequest(@NotBlank String title, @NotBlank String venue, @NotBlank String category,
                                     @NotNull @Future Instant startsAt, @Min(0) int priceCents,
                                     @Min(1) @Max(26) int rows, @Min(1) @Max(40) int seatsPerRow,
                                     @Size(max = 1000) String description,
                                     @Min(0) @Max(26) Integer vipRows, @Min(0) Integer vipPriceCents,
                                     @Min(0) @Max(26) Integer premiumRows, @Min(0) Integer premiumPriceCents) {
        /** Plain event with one price for every seat. */
        public CreateEventRequest(String title, String venue, String category, Instant startsAt, int priceCents,
                                  int rows, int seatsPerRow, String description) {
            this(title, venue, category, startsAt, priceCents, rows, seatsPerRow, description, null, null, null, null);
        }

        public int vip() { return vipRows == null ? 0 : vipRows; }
        public int premium() { return premiumRows == null ? 0 : premiumRows; }
    }

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(max = 60) String name,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record AuthResponse(String token, UserDto user) {}
}
