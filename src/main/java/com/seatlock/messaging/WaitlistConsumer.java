package com.seatlock.messaging;

import com.seatlock.api.Dtos.SeatsReleased;
import com.seatlock.realtime.SeatUpdates;
import com.seatlock.service.WaitlistService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes seats.released: the waitlist gets first claim on freed seats, then seat maps are told
 * something changed, then the people offered a seat are emailed (after the offer transaction commits).
 * Safe to redeliver: seats already held or booked are skipped by offer().
 */
@Component
class WaitlistConsumer {
    private final WaitlistService waitlist;
    private final SeatUpdates updates;
    private final Mailer mailer;

    WaitlistConsumer(WaitlistService waitlist, SeatUpdates updates, Mailer mailer) {
        this.waitlist = waitlist;
        this.updates = updates;
        this.mailer = mailer;
    }

    @RabbitListener(queues = RabbitConfig.SEATS_RELEASED)
    public void handle(SeatsReleased msg) {
        var offers = waitlist.offer(msg.eventId(), msg.seatIds());
        updates.publish(msg.eventId());
        offers.forEach(o -> mailer.waitlistOffer(o.email(), o.eventTitle(), o.eventId(), o.seat(), o.expiresAt()));
    }
}
