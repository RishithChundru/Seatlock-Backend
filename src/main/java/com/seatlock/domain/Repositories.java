package com.seatlock.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class Repositories {
    private Repositories() {}

    public interface UserRepository extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByEmail(String email);
        boolean existsByEmail(String email);
        List<AppUser> findAllByStaffOfOrderByNameAsc(Long organizerId);
        List<AppUser> findAllByRole(AppUser.Role role);
    }

    public interface OrganizerRepository extends JpaRepository<Organizer, Long> {
        Optional<Organizer> findByUserId(Long userId);
        List<Organizer> findAllByOrderByCreatedAtDesc();
        List<Organizer> findAllByStatusOrderByCreatedAtAsc(Organizer.Status status);
        boolean existsByNameIgnoreCaseAndUserIdNot(String name, Long userId);
        boolean existsByNameIgnoreCase(String name);
    }

    public interface EventRepository extends JpaRepository<Event, Long> {
        List<Event> findAllByOrderByStartsAtAsc();
        List<Event> findAllByOrganizerIdOrderByStartsAtAsc(Long organizerId);
    }

    public interface EventImageRepository extends JpaRepository<EventImage, Long> {}

    public interface OrganizerLogoRepository extends JpaRepository<OrganizerLogo, Long> {}

    public interface StaffInviteRepository extends JpaRepository<StaffInvite, Long> {
        Optional<StaffInvite> findByTokenHash(String tokenHash);
        List<StaffInvite> findAllByOrganizerIdAndUsedAtIsNullOrderByCreatedAtAsc(Long organizerId);
        void deleteByOrganizerIdAndEmailAndUsedAtIsNull(Long organizerId, String email);
    }

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

    public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
        // The user is always needed (access token claims, /session) and often used after the transaction ends.
        @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "user")
        Optional<RefreshToken> findByTokenHash(String tokenHash);

        @Modifying(clearAutomatically = true)
        @Query("update RefreshToken t set t.revokedAt = :at where t.familyId = :family and t.revokedAt is null")
        int revokeFamily(@Param("family") java.util.UUID family, @Param("at") Instant at);

        @Modifying(clearAutomatically = true)
        @Query("update RefreshToken t set t.revokedAt = :at where t.user.id = :userId and t.revokedAt is null and t.familyId <> :keep")
        int revokeOtherFamilies(@Param("userId") Long userId, @Param("keep") java.util.UUID keep, @Param("at") Instant at);
    }

    public interface BookingRepository extends JpaRepository<Booking, Long> {
        List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
        Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
        List<Booking> findTop100ByTicketIssuedAtIsNullAndCreatedAtBefore(Instant cutoff);
        Optional<Booking> findByTicketCode(String ticketCode);
        Optional<Booking> findByShareToken(String shareToken);
        boolean existsByEventIdAndStatus(Long eventId, Booking.Status status);
        List<Booking> findByEventIdAndStatus(Long eventId, Booking.Status status);

        /** Per event: [eventId, confirmed bookings, revenue in paise, bookings checked in]. */
        @Query("select b.event.id, count(b), coalesce(sum(b.totalCents), 0), count(b.checkedInAt) from Booking b "
                + "where b.status = com.seatlock.domain.Booking.Status.CONFIRMED and b.event.id in :ids group by b.event.id")
        List<Object[]> statsFor(@Param("ids") List<Long> eventIds);

        /** [createdAt, totalCents] of confirmed bookings for these events since a time (dashboard sales chart). */
        @Query("select b.createdAt, b.totalCents from Booking b where b.event.id in :ids "
                + "and b.status = com.seatlock.domain.Booking.Status.CONFIRMED and b.createdAt >= :since")
        List<Object[]> salesSince(@Param("ids") List<Long> eventIds, @Param("since") Instant since);

        @Query("select b from Booking b join fetch b.event join fetch b.user where b.event.id in :ids order by b.createdAt desc")
        List<Booking> latestFor(@Param("ids") List<Long> eventIds, org.springframework.data.domain.Pageable page);
        void deleteByEventId(Long eventId);

        /**
         * Check-in is one conditional UPDATE, so two gate scanners reading the same ticket at the
         * same moment can't both admit it: exactly one gets 1 row, the other 0.
         */
        @Modifying(clearAutomatically = true) // so the re-read after the update sees checkedInAt
        @Query("update Booking b set b.checkedInAt = :at where b.id = :id and b.checkedInAt is null and b.status = com.seatlock.domain.Booking.Status.CONFIRMED")
        int checkIn(@Param("id") Long id, @Param("at") Instant at);
    }

    public interface PaymentRepository extends JpaRepository<Payment, Long> {
        Optional<Payment> findByOrderId(String orderId);
        Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
        Optional<Payment> findByBookingId(Long bookingId);

        /** CREATED -> PAID. Returns 0 if the other path (verify vs webhook) already did it. */
        @Transactional
        @Modifying(clearAutomatically = true)
        @Query("update Payment p set p.status = com.seatlock.domain.Payment.Status.PAID, p.paymentId = :paymentId, p.bookingId = :bookingId "
                + "where p.id = :id and p.status = com.seatlock.domain.Payment.Status.CREATED")
        int markPaid(@Param("id") Long id, @Param("paymentId") String paymentId, @Param("bookingId") Long bookingId);

        /** from -> REFUNDED. 1 means this caller owns the refund, so it's issued exactly once. */
        @Transactional
        @Modifying(clearAutomatically = true)
        @Query("update Payment p set p.status = com.seatlock.domain.Payment.Status.REFUNDED, p.paymentId = :paymentId "
                + "where p.id = :id and p.status = :from")
        int markRefunded(@Param("id") Long id, @Param("paymentId") String paymentId, @Param("from") Payment.Status from);
    }
}
