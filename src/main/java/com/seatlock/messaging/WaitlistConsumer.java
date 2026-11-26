package com.seatlock.messaging;

import com.seatlock.api.Dtos.SeatsReleased;
import com.seatlock.realtime.SeatUpdates;
import com.seatlock.service.WaitlistService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes seats.released: the waitlist gets first claim on freed seats, and only then are seat maps
 * told something changed. Safe to redeliver: seats already held or booked are skipped by offer().
 */
@Component
class WaitlistConsumer {
    private final WaitlistService waitlist;
    private final SeatUpdates updates;

    WaitlistConsumer(WaitlistService waitlist, SeatUpdates updates) {
        this.waitlist = waitlist;
        this.updates = updates;
    }

    @RabbitListener(queues = RabbitConfig.SEATS_RELEASED)
    public void handle(SeatsReleased msg) {
        waitlist.offer(msg.eventId(), msg.seatIds());
        updates.publish(msg.eventId());
    }
}
