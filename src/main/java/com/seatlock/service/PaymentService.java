package com.seatlock.service;

import com.seatlock.api.Dtos.BookingDto;
import com.seatlock.api.Dtos.CheckoutResponse;
import com.seatlock.domain.Payment;
import com.seatlock.domain.Repositories.PaymentRepository;
import com.seatlock.service.BookingService.BookingCancelled;
import com.seatlock.service.BookingService.SeatUnavailableException;
import com.seatlock.service.SeatRows.SeatRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checkout with Razorpay. The seats stay held in Redis while the user pays; the booking is made when the
 * payment is proven, by the browser's signed callback (verify) or Razorpay's signed webhook, whichever comes
 * first. Both paths are idempotent. If the seats were lost meanwhile, the payment is refunded in full.
 * Never holds a DB transaction open across a Razorpay call.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    /** Long enough to finish Checkout (its own timeout is shorter, see the frontend). */
    public static final Duration PAYMENT_HOLD = Duration.ofMinutes(15);

    public static class PaymentRefundedException extends RuntimeException {
        public PaymentRefundedException(String message) { super(message); }
    }

    private final RazorpayClient razorpay;
    private final PaymentRepository payments;
    private final BookingService bookings;
    private final SeatHoldService holds;
    private final SeatRows seatRows;
    private final JsonMapper json;

    public PaymentService(RazorpayClient razorpay, PaymentRepository payments, BookingService bookings,
                          SeatHoldService holds, SeatRows seatRows, JsonMapper json) {
        this.razorpay = razorpay;
        this.payments = payments;
        this.bookings = bookings;
        this.holds = holds;
        this.seatRows = seatRows;
        this.json = json;
    }

    public boolean enabled() { return razorpay.enabled(); }

    /** Payments off (no keys) or a free order: books right away. Otherwise opens a Razorpay order for the held seats. */
    public CheckoutResponse checkout(Long userId, Long eventId, List<Long> seatIds, String idempotencyKey) {
        if (!enabled()) return CheckoutResponse.booked(bookings.book(userId, eventId, seatIds, idempotencyKey));
        if (idempotencyKey != null) {
            var previous = payments.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (previous.isPresent()) return resume(previous.get());
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) throw new IllegalArgumentException("Duplicate seats in request");
        Map<Long, SeatRow> rows = seatRows.forEvent(eventId).stream().collect(Collectors.toMap(SeatRow::id, Function.identity()));
        if (!rows.keySet().containsAll(seatIds)) throw new IllegalArgumentException("Seats do not belong to this event");
        if (seatIds.stream().anyMatch(id -> rows.get(id).booked())) throw new SeatUnavailableException();

        holds.extend(userId, seatIds, PAYMENT_HOLD); // must already hold them; keeps them ours while paying
        int amount = seatIds.stream().mapToInt(id -> rows.get(id).priceCents()).sum(); // server-side price, never the client's
        if (amount == 0) return CheckoutResponse.booked(bookings.book(userId, eventId, seatIds, idempotencyKey));

        String orderId = razorpay.createOrder(amount, "e" + eventId + "-u" + userId + "-" + System.currentTimeMillis());
        Payment p = payments.save(new Payment(userId, eventId, seatIds, amount, orderId, idempotencyKey));
        return CheckoutResponse.order(p.getOrderId(), p.getAmountCents(), razorpay.keyId());
    }

    /** The browser's Checkout success callback. The signature proves Razorpay produced this payment for this order. */
    public BookingDto verify(Long userId, String orderId, String paymentId, String signature) {
        Payment p = payments.findByOrderId(orderId).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new NoSuchElementException("Payment not found"));
        if (!razorpay.validPaymentSignature(orderId, paymentId, signature))
            throw new IllegalArgumentException("This payment couldn't be verified");
        return complete(p, paymentId);
    }

    /** Razorpay's server-to-server notification: books even if the user closed the tab before verify ran. */
    public void webhook(String body, String signature) {
        if (!razorpay.validWebhookSignature(body, signature)) throw new IllegalArgumentException("Invalid webhook signature");
        JsonNode root = json.readTree(body);
        if (!"payment.captured".equals(root.path("event").asString())) return; // the only event we act on
        JsonNode payment = root.path("payload").path("payment").path("entity");
        payments.findByOrderId(payment.path("order_id").asString()).ifPresent(p -> {
            try {
                complete(p, payment.path("id").asString());
            } catch (PaymentRefundedException e) {
                // Handled: refunded. Answer 200 so Razorpay doesn't retry.
            }
        });
    }

    private CheckoutResponse resume(Payment p) {
        return switch (p.getStatus()) {
            case PAID -> CheckoutResponse.booked(bookings.book(p.getUserId(), p.getEventId(), p.seatIds(), bookingKey(p)));
            case CREATED -> {
                holds.extend(p.getUserId(), p.seatIds(), PAYMENT_HOLD);
                yield CheckoutResponse.order(p.getOrderId(), p.getAmountCents(), razorpay.keyId());
            }
            case REFUNDED -> throw new PaymentRefundedException("This checkout was refunded. Pick your seats again.");
        };
    }

    private BookingDto complete(Payment p, String paymentId) {
        if (p.getStatus() == Payment.Status.REFUNDED)
            throw new PaymentRefundedException("This payment was refunded. Pick your seats again.");
        BookingDto booking;
        try {
            booking = book(p);
        } catch (SeatUnavailableException | ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            try {
                booking = book(p); // verify and webhook raced: the winner's booking is returned by its idempotency key
            } catch (SeatUnavailableException | ObjectOptimisticLockingFailureException | DataIntegrityViolationException lost) {
                // Hold expired and someone else bought the seats while this user paid: give the money back.
                if (payments.markRefunded(p.getId(), paymentId, Payment.Status.CREATED) == 1) refund(paymentId, p.getAmountCents());
                throw new PaymentRefundedException("Those seats were taken while you were paying. Your payment has been refunded in full.");
            }
        }
        payments.markPaid(p.getId(), paymentId, booking.id());
        return booking;
    }

    private void refund(String paymentId, int amount) {
        try {
            razorpay.refund(paymentId, amount);
            log.info("Refunded payment {}", paymentId);
        } catch (RazorpayClient.ProviderException ex) {
            // shortcut: no automatic retry; our row already says REFUNDED, so finish it from the Razorpay dashboard. Add a retry job when volume justifies it.
            log.error("Refund of payment {} failed; refund it from the Razorpay dashboard", paymentId, ex);
        }
    }

    private BookingDto book(Payment p) { return bookings.book(p.getUserId(), p.getEventId(), p.seatIds(), bookingKey(p)); }

    private static String bookingKey(Payment p) { return "rzp:" + p.getOrderId(); }

    /**
     * Cancelling a paid booking refunds it. Runs inside the cancel transaction (status change commits with it);
     * the Razorpay call happens after commit, so a rollback never refunds and the call holds no DB connection.
     */
    @EventListener
    void onCancelled(BookingCancelled e) {
        payments.findByBookingId(e.bookingId()).filter(p -> p.getStatus() == Payment.Status.PAID).ifPresent(p -> {
            p.refunded();
            String paymentId = p.getPaymentId();
            int amount = p.getAmountCents();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { refund(paymentId, amount); }
            });
        });
    }
}
