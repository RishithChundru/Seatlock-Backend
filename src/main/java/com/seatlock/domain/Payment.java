package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** A Razorpay order for a set of held seats. Status only moves forward, via conditional UPDATEs. */
@Entity
@Table(name = "payments")
public class Payment {
    public enum Status { CREATED, PAID, REFUNDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false)
    private Long eventId;
    @Column(nullable = false, length = 200)
    private String seatIds;
    @Column(nullable = false)
    private int amountCents;
    @Column(name = "razorpay_order_id", nullable = false, length = 40)
    private String orderId;
    @Column(name = "razorpay_payment_id", length = 40)
    private String paymentId;
    private Long bookingId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.CREATED;
    @Column(length = 100)
    private String idempotencyKey;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Payment() {}

    public Payment(Long userId, Long eventId, List<Long> seatIds, int amountCents, String orderId, String idempotencyKey) {
        this.userId = userId;
        this.eventId = eventId;
        this.seatIds = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        this.amountCents = amountCents;
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public List<Long> seatIds() { return Arrays.stream(seatIds.split(",")).map(Long::valueOf).toList(); }
    public int getAmountCents() { return amountCents; }
    public String getOrderId() { return orderId; }
    public String getPaymentId() { return paymentId; }
    public Long getBookingId() { return bookingId; }
    public Status getStatus() { return status; }

    /** For use inside a transaction that already owns this row (booking cancellation). */
    public void refunded() { this.status = Status.REFUNDED; }
}
