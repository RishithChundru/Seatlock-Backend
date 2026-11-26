package com.seatlock.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    public interface EventImageRepository extends JpaRepository<EventImage, Long> {}

    public interface SeatRepository extends JpaRepository<Seat, Long> {
        List<Seat> findByEventIdOrderByRowLabelAscSeatNumberAsc(Long eventId);
        long countByEventId(Long eventId);
        long countByEventIdAndStatus(Long eventId, Seat.Status status);
        void deleteByEventId(Long eventId);
    }

    public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {
        Optional<WaitlistEntry> findByEventIdAndUserId(Long eventId, Long userId);
        Optional<WaitlistEntry> findFirstByEventIdAndOfferedSeatIdIsNullOrderByIdAsc(Long eventId);
        long countByEventIdAndOfferedSeatIdIsNullAndIdLessThanEqual(Long eventId, Long id);
        List<WaitlistEntry> findByUserIdOrderByCreatedAtDesc(Long userId);
        void deleteByEventIdAndUserId(Long eventId, Long userId);
        void deleteByEventId(Long eventId);
    }

    public interface BookingRepository extends JpaRepository<Booking, Long> {
        List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
        Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
        List<Booking> findTop100ByTicketIssuedAtIsNullAndCreatedAtBefore(Instant cutoff);
        Optional<Booking> findByTicketCode(String ticketCode);
        boolean existsByEventIdAndStatus(Long eventId, Booking.Status status);
        void deleteByEventId(Long eventId);

        /**
         * Check-in is one conditional UPDATE, so two gate scanners reading the same ticket at the
         * same moment can't both admit it: exactly one gets 1 row, the other 0.
         */
        @Modifying(clearAutomatically = true) // so the re-read after the update sees checkedInAt
        @Query("update Booking b set b.checkedInAt = :at where b.id = :id and b.checkedInAt is null and b.status = com.seatlock.domain.Booking.Status.CONFIRMED")
        int checkIn(@Param("id") Long id, @Param("at") Instant at);
    }
}
