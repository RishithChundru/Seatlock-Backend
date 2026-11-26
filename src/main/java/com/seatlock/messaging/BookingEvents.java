package com.seatlock.messaging;

import com.seatlock.api.Dtos.BookingConfirmed;
import com.seatlock.domain.Booking;
import com.seatlock.domain.Repositories.BookingRepository;
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

    BookingEvents(RabbitTemplate rabbit, BookingRepository bookings, SeatHoldService holds) {
        this.rabbit = rabbit;
        this.bookings = bookings;
        this.holds = holds;
    }

    /** Runs only after the booking transaction commits, so we never announce a booking that rolled back. */
    @TransactionalEventListener
    @Caching(evict = {@CacheEvict(value = EventService.LIST_CACHE, allEntries = true),
                      @CacheEvict(value = EventService.ITEM_CACHE, key = "#e.eventId")})
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    public void onCommit(BookingCommitted e) {
        e.seatIds().forEach(id -> holds.release(e.userId(), id));
        bookings.findById(e.bookingId()).ifPresent(this::publish);
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
            var msg = new BookingConfirmed(b.getId(), b.getUser().getEmail(), b.getEvent().getTitle(),
                    b.getSeats().stream().map(s -> s.getRowLabel() + s.getSeatNumber()).toList());
            rabbit.convertAndSend(RabbitConfig.QUEUE, msg);
        } catch (RuntimeException ex) {
            log.error("Could not publish booking {}; the sweeper will retry", b.getId(), ex);
        }
    }
}
