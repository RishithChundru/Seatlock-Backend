package com.seatlock.messaging;

import com.seatlock.api.Dtos.BookingConfirmed;
import com.seatlock.domain.Repositories.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/** Consumes booking.confirmed: issues the ticket code and "emails" it. Idempotent, so redelivery is safe. */
@Component
class TicketIssuer {
    private static final Logger log = LoggerFactory.getLogger(TicketIssuer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookings;

    TicketIssuer(BookingRepository bookings) { this.bookings = bookings; }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    @Transactional
    public void handle(BookingConfirmed msg) {
        var booking = bookings.findById(msg.bookingId())
                .orElseThrow(() -> new IllegalStateException("Booking " + msg.bookingId() + " not found"));
        if (booking.isTicketIssued()) return; // duplicate delivery

        booking.issueTicket(code());
        // shortcut: logs instead of sending real email; plug in an SMTP/SES client here
        log.info("Ticket {} emailed to {} for {} seats {}", booking.getTicketCode(), msg.email(), msg.eventTitle(), msg.seats());
    }

    private static String code() {
        StringBuilder sb = new StringBuilder("SL-");
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
