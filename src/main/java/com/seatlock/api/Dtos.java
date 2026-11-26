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
    public record EventDto(Long id, String title, String venue, String category, Instant startsAt,
                           int priceCents, long totalSeats, long availableSeats) implements Serializable {}

    /** status: AVAILABLE, BOOKED, or HELD (in someone's 5-minute checkout hold). mine = held by the caller. */
    public record SeatDto(Long id, String row, int number, String status, boolean mine) {}

    public record BookingDto(Long id, Long eventId, String eventTitle, String venue, Instant startsAt,
                             int totalCents, Instant createdAt, List<String> seats, String ticketCode) {
        public static BookingDto of(Booking b) {
            return new BookingDto(b.getId(), b.getEvent().getId(), b.getEvent().getTitle(), b.getEvent().getVenue(),
                    b.getEvent().getStartsAt(), b.getTotalCents(), b.getCreatedAt(),
                    b.getSeats().stream().map(s -> s.getRowLabel() + s.getSeatNumber()).toList(), b.getTicketCode());
        }
    }

    public record HoldRequest(@NotNull Long eventId, @NotEmpty @Size(max = 10) List<@NotNull Long> seatIds) {}

    public record HoldResponse(List<Long> seatIds, Instant expiresAt) {}

    /** Published to RabbitMQ after a booking commits. */
    public record BookingConfirmed(Long bookingId, String email, String eventTitle, List<String> seats) {}

    public record BookingRequest(@NotNull Long eventId, @NotEmpty @Size(max = 10) List<@NotNull Long> seatIds) {}

    public record CreateEventRequest(@NotBlank String title, @NotBlank String venue, @NotBlank String category,
                                     @NotNull @Future Instant startsAt, @Min(0) int priceCents,
                                     @Min(1) @Max(26) int rows, @Min(1) @Max(40) int seatsPerRow) {}

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(max = 60) String name,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record AuthResponse(String token, UserDto user) {}
}
