package com.seatlock.api;

import com.seatlock.domain.*;
import jakarta.validation.constraints.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record UserDto(Long id, String email, String name, String role) {
        public static UserDto of(AppUser u) { return new UserDto(u.getId(), u.getEmail(), u.getName(), u.getRole().name()); }
    }

    // Serializable: cached in Redis
    /** imageUrl is versioned (?v=) so browsers can cache it forever; null when no cover was uploaded. */
    public record EventDto(Long id, String title, String venue, String category, Instant startsAt, int priceCents,
                           long totalSeats, long availableSeats, String description, String imageUrl,
                           String city, List<TierPrice> tiers) implements Serializable {}

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
                             String status, Instant cancelledAt, Instant checkedInAt) {
        public static BookingDto of(Booking b) {
            return new BookingDto(b.getId(), b.getEvent().getId(), b.getEvent().getTitle(), b.getEvent().getVenue(),
                    b.getEvent().getStartsAt(), b.getTotalCents(), b.getCreatedAt(), b.seatLabels(), b.getTicketCode(),
                    b.getStatus().name(), b.getCancelledAt(), b.getCheckedInAt());
        }
    }

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
