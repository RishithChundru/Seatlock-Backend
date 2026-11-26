package com.seatlock.api;

import com.seatlock.domain.*;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record UserDto(Long id, String email, String name, String role) {
        public static UserDto of(AppUser u) { return new UserDto(u.getId(), u.getEmail(), u.getName(), u.getRole().name()); }
    }

    public record EventDto(Long id, String title, String venue, String category, Instant startsAt,
                           int priceCents, long totalSeats, long availableSeats) {}

    public record SeatDto(Long id, String row, int number, String status) {
        public static SeatDto of(Seat s) { return new SeatDto(s.getId(), s.getRowLabel(), s.getSeatNumber(), s.getStatus().name()); }
    }

    public record BookingDto(Long id, Long eventId, String eventTitle, String venue, Instant startsAt,
                             int totalCents, Instant createdAt, List<String> seats) {
        public static BookingDto of(Booking b) {
            return new BookingDto(b.getId(), b.getEvent().getId(), b.getEvent().getTitle(), b.getEvent().getVenue(),
                    b.getEvent().getStartsAt(), b.getTotalCents(), b.getCreatedAt(),
                    b.getSeats().stream().map(s -> s.getRowLabel() + s.getSeatNumber()).toList());
        }
    }

    public record BookingRequest(@NotNull Long eventId, @NotEmpty @Size(max = 10) List<@NotNull Long> seatIds) {}

    public record CreateEventRequest(@NotBlank String title, @NotBlank String venue, @NotBlank String category,
                                     @NotNull @Future Instant startsAt, @Min(0) int priceCents,
                                     @Min(1) @Max(26) int rows, @Min(1) @Max(40) int seatsPerRow) {}

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(max = 60) String name,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record AuthResponse(String token, UserDto user) {}
}
