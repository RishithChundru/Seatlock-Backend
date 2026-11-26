package com.seatlock.messaging;

import com.seatlock.api.Dtos.BookingConfirmed;
import com.seatlock.domain.Repositories.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/** Consumes booking.confirmed: issues the ticket code and emails it. Idempotent, so redelivery is safe. */
@Component
class TicketIssuer {
    private static final Logger log = LoggerFactory.getLogger(TicketIssuer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookings;
    private final TransactionTemplate tx;
    private final Mailer mailer;

    TicketIssuer(BookingRepository bookings, TransactionTemplate tx, Mailer mailer) {
        this.bookings = bookings;
        this.tx = tx;
        this.mailer = mailer;
    }

    private record Issued(String email, String name, String title, Instant startsAt, String venue, List<String> seats, String code) {}

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void handle(BookingConfirmed msg) {
        Issued issued = tx.execute(status -> {
            var booking = bookings.findById(msg.bookingId())
                    .orElseThrow(() -> new IllegalStateException("Booking " + msg.bookingId() + " not found"));
            if (booking.isTicketIssued()) return null; // duplicate delivery
            booking.issueTicket(code());
            var e = booking.getEvent();
            return new Issued(booking.getUser().getEmail(), booking.getUser().getName(), e.getTitle(), e.getStartsAt(),
                    e.getVenue(), booking.seatLabels(), booking.getTicketCode());
        });
        // After commit, so a slow mail server never holds a DB connection (or rolls back an issued ticket).
        if (issued != null) {
            log.info("Ticket {} issued for booking {}", issued.code(), msg.bookingId());
            mailer.ticketIssued(issued.email(), issued.name(), issued.title(), issued.startsAt(), issued.venue(), issued.seats(), issued.code());
        }
    }

    private static String code() {
        StringBuilder sb = new StringBuilder("SL-");
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
