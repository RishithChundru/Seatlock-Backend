package com.seatlock.messaging;

import com.seatlock.api.Dtos.BookingConfirmed;
import com.seatlock.api.Dtos.SeatsReleased;
import com.seatlock.domain.Booking;
import com.seatlock.domain.Repositories.BookingRepository;
import com.seatlock.realtime.SeatUpdates;
import com.seatlock.service.BookingService.BookingCancelled;
import com.seatlock.service.BookingService.BookingCommitted;
import com.seatlock.service.EventService;
import com.seatlock.service.SeatHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

@Component
class BookingEvents {
    private static final Logger log = LoggerFactory.getLogger(BookingEvents.class);

    private final RabbitTemplate rabbit;
    private final BookingRepository bookings;
    private final SeatHoldService holds;
    private final SeatUpdates updates;

    BookingEvents(RabbitTemplate rabbit, BookingRepository bookings, SeatHoldService holds, SeatUpdates updates) {
        this.rabbit = rabbit;
        this.bookings = bookings;
        this.holds = holds;
        this.updates = updates;
    }

    /** Runs only after the booking transaction commits, so we never announce a booking that rolled back. */
    @TransactionalEventListener
    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, key = "#e.eventId")})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onCommit(BookingCommitted e) {
        e.seatIds().forEach(id -> holds.release(e.userId(), id));
        bookings.findById(e.bookingId()).ifPresent(this::publish);
        updates.publish(e.eventId());
    }

    /**
     * After a cancellation commits: hand the freed seats to the waitlist consumer. Seat maps are pinged by
     * the consumer once offers are made, so people in line get first claim. If RabbitMQ is down the seats
     * are simply back on general sale.
     */
    @TransactionalEventListener
    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, key = "#e.eventId")})
    public void onCancel(BookingCancelled e) {
        try {
            rabbit.convertAndSend(RabbitConfig.SEATS_RELEASED, new SeatsReleased(e.eventId(), e.seatIds()));
        } catch (RuntimeException ex) {
            log.error("Could not publish released seats for booking {}; they are back on general sale", e.bookingId(), ex);
            updates.publish(e.eventId());
        }
    }

    /**
     * If RabbitMQ was down at commit time the message above is lost. This sweeper re-publishes any booking
     * still without a ticket; the consumer is idempotent, so a duplicate is harmless.
     * shortcut: polling sweep instead of a transactional outbox table, fine at this scale
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional(readOnly = true)
    public void republishStuck() {
        for (Booking b : bookings.findTop100ByTicketIssuedAtIsNullAndCreatedAtBefore(Instant.now().minus(Duration.ofMinutes(1)))) {
            log.warn("Re-publishing booking {} with no ticket yet", b.getId());
            publish(b);
        }
    }

    private void publish(Booking b) {
        try {
            var msg = new BookingConfirmed(b.getId(), b.getUser().getEmail(), b.getEvent().getTitle(), b.seatLabels());
            rabbit.convertAndSend(RabbitConfig.QUEUE, msg);
        } catch (RuntimeException ex) {
            log.error("Could not publish booking {}; the sweeper will retry", b.getId(), ex);
        }
    }
}
