package com.seatlock.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Refunds run after the cancel commits, on a worker thread: no request thread or DB connection waits on Razorpay. */
@Component
class RefundWorker {
    private final PaymentService payments;

    RefundWorker(PaymentService payments) { this.payments = payments; }

    @Async
    @TransactionalEventListener
    public void onRefund(PaymentService.RefundRequested r) { payments.refund(r.paymentId(), r.amountCents()); }
}
