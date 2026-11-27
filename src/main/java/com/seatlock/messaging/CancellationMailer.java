package com.seatlock.messaging;

import com.seatlock.domain.Payment;
import com.seatlock.domain.Repositories.PaymentRepository;
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
    private final PaymentRepository payments;

    CancellationMailer(Mailer mailer, PaymentRepository payments) {
        this.mailer = mailer;
        this.payments = payments;
    }

    @Async
    @TransactionalEventListener
    public void onCancel(BookingCancelled e) {
        // Worker thread, after commit: the payment row already says REFUNDED if this booking was paid for.
        boolean refunded = payments.findByBookingId(e.bookingId()).map(p -> p.getStatus() == Payment.Status.REFUNDED).orElse(false);
        mailer.bookingCancelled(e.email(), e.eventTitle(), e.seatLabels(), e.refundCents(), refunded, e.eventCancelled());
    }
}
