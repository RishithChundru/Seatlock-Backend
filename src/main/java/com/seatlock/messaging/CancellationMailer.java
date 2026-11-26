package com.seatlock.messaging;

import com.seatlock.service.BookingService.BookingCancelled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cancellation confirmation email. @Async: after-commit listeners still run while the request's DB
 * connection is checked out, so a slow SMTP server must not be waited on in that thread.
 */
@Component
class CancellationMailer {
    private final Mailer mailer;

    CancellationMailer(Mailer mailer) { this.mailer = mailer; }

    @Async
    @TransactionalEventListener
    public void onCancel(BookingCancelled e) {
        mailer.bookingCancelled(e.email(), e.eventTitle(), e.seatLabels(), e.refundCents());
    }
}
