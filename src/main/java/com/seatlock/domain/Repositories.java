package com.seatlock.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class Repositories {
    private Repositories() {}

    public interface UserRepository extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByEmail(String email);
        boolean existsByEmail(String email);
    }

    public interface EventRepository extends JpaRepository<Event, Long> {
        List<Event> findAllByOrderByStartsAtAsc();
    }

    public interface SeatRepository extends JpaRepository<Seat, Long> {
        List<Seat> findByEventIdOrderByRowLabelAscSeatNumberAsc(Long eventId);
        long countByEventId(Long eventId);
        long countByEventIdAndStatus(Long eventId, Seat.Status status);
    }

    public interface BookingRepository extends JpaRepository<Booking, Long> {
        List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
        Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    }
}
