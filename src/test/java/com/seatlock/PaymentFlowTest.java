package com.seatlock;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Payment;
import com.seatlock.domain.Repositories.PaymentRepository;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventService;
import com.seatlock.service.PaymentService;
import com.seatlock.service.SeatHoldService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Razorpay checkout end to end against a fake Razorpay API (orders + refunds), with real Postgres/Redis/RabbitMQ. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PaymentFlowTest {
    static final String KEY_SECRET = "test-key-secret";
    static final String WEBHOOK_SECRET = "test-webhook-secret";
    static final List<String> refunds = new CopyOnWriteArrayList<>();
    static final AtomicInteger orders = new AtomicInteger();
    static final HttpServer fakeRazorpay = startFake();

    static HttpServer startFake() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/v1/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = path.equals("/v1/orders") ? "{\"id\":\"order_T" + orders.incrementAndGet() + "\"}" : "{\"id\":\"rfnd_1\"}";
                if (path.endsWith("/refund")) refunds.add(path.split("/")[3]);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void razorpay(DynamicPropertyRegistry r) {
        r.add("app.payments.razorpay.key-id", () -> "rzp_test_fake");
        r.add("app.payments.razorpay.key-secret", () -> KEY_SECRET);
        r.add("app.payments.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("app.payments.razorpay.api-url", () -> "http://localhost:" + fakeRazorpay.getAddress().getPort());
    }

    @AfterAll
    static void stopFake() { fakeRazorpay.stop(0); }

    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRows;
    @Autowired BookingService bookings;
    @Autowired SeatHoldService holds;
    @Autowired EventService events;
    @Autowired UserRepository users;
    @Autowired StringRedisTemplate redis;

    static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private Long user() {
        return users.save(new AppUser("p-" + UUID.randomUUID() + "@test.local", "Payer", "x", AppUser.Role.USER)).getId();
    }

    /** A fresh event and its first two seats, held by the user. */
    private record Setup(Long eventId, List<Long> seatIds) {}

    private Setup heldSeats(Long userId) {
        var ev = events.create(new CreateEventRequest("Paid " + UUID.randomUUID(), "Hall, Test City", "Concert",
                Instant.now().plus(Duration.ofDays(5)), 50000, 1, 3, null));
        List<Long> ids = events.seats(ev.id(), userId).stream().limit(2).map(s -> s.id()).toList();
        holds.hold(userId, ev.id(), ids);
        return new Setup(ev.id(), ids);
    }

    private static String captured(String orderId, String paymentId) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + orderId + "\",\"amount\":100000}}}}";
    }

    @Test
    void paidCheckoutBooksExactlyOnceWhicheverConfirmationArrivesFirst() throws Exception {
        Long u = user();
        Setup s = heldSeats(u);

        var order = payments.checkout(u, s.eventId(), s.seatIds(), "key-1");
        assertThat(order.booking()).isNull();
        assertThat(order.amountCents()).isEqualTo(100000); // priced on the server: 2 x ₹500
        assertThat(order.keyId()).isEqualTo("rzp_test_fake");
        assertThat(bookings.forUser(u)).isEmpty();
        assertThat(redis.getExpire("hold:seat:" + s.seatIds().get(0))).isGreaterThan(SeatHoldService.TTL.toSeconds()); // extended

        // A forged callback books nothing.
        assertThatThrownBy(() -> payments.verify(u, order.orderId(), "pay_1", "forged")).isInstanceOf(IllegalArgumentException.class);
        assertThat(bookings.forUser(u)).isEmpty();

        var booking = payments.verify(u, order.orderId(), "pay_1", hmac(KEY_SECRET, order.orderId() + "|pay_1"));
        assertThat(booking.status()).isEqualTo("CONFIRMED");

        // The webhook for the same payment, and a retried checkout, don't book twice.
        String body = captured(order.orderId(), "pay_1");
        payments.webhook(body, hmac(WEBHOOK_SECRET, body));
        assertThat(payments.checkout(u, s.eventId(), s.seatIds(), "key-1").booking().id()).isEqualTo(booking.id());
        assertThat(bookings.forUser(u)).hasSize(1);
        assertThat(paymentRows.findByOrderId(order.orderId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.PAID);

        // Cancelling a paid booking refunds it.
        bookings.cancel(u, booking.id());
        eventually(() -> assertThat(refunds).contains("pay_1")); // refunds run on a worker thread after commit
        assertThat(paymentRows.findByOrderId(order.orderId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void theWebhookAloneBooksWhenTheTabWasClosedAndForgedWebhooksAreRejected() throws Exception {
        Long u = user();
        Setup s = heldSeats(u);
        var order = payments.checkout(u, s.eventId(), s.seatIds(), null);
        String body = captured(order.orderId(), "pay_2");

        assertThatThrownBy(() -> payments.webhook(body, hmac("wrong-secret", body))).isInstanceOf(IllegalArgumentException.class);
        assertThat(bookings.forUser(u)).isEmpty();

        payments.webhook(body, hmac(WEBHOOK_SECRET, body));
        assertThat(bookings.forUser(u)).singleElement().satisfies(b -> assertThat(b.status()).isEqualTo("CONFIRMED"));
    }

    @Test
    void seatsLostWhilePayingAreRefundedInFull() throws Exception {
        Long alice = user();
        Long bob = user();
        Setup s = heldSeats(alice);
        var order = payments.checkout(alice, s.eventId(), s.seatIds(), null);

        // Alice's hold lapses mid-payment and Bob books the same seats (payments bypassed at the service layer).
        s.seatIds().forEach(id -> redis.delete("hold:seat:" + id));
        holds.hold(bob, s.eventId(), s.seatIds());
        bookings.book(bob, s.eventId(), s.seatIds(), null);

        assertThatThrownBy(() -> payments.verify(alice, order.orderId(), "pay_3", hmac(KEY_SECRET, order.orderId() + "|pay_3")))
                .isInstanceOf(PaymentService.PaymentRefundedException.class);
        assertThat(refunds).contains("pay_3");
        assertThat(bookings.forUser(alice)).isEmpty();
        assertThat(paymentRows.findByOrderId(order.orderId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void cancellingAnEventRefundsEveryPaidTicket() throws Exception {
        Long u = user();
        Setup s = heldSeats(u);
        var order = payments.checkout(u, s.eventId(), s.seatIds(), null);
        payments.verify(u, order.orderId(), "pay_4", hmac(KEY_SECRET, order.orderId() + "|pay_4"));

        events.delete(s.eventId());
        eventually(() -> assertThat(refunds).contains("pay_4"));
        assertThat(paymentRows.findByOrderId(order.orderId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    static void eventually(Runnable check) throws InterruptedException {
        for (int i = 0; ; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (i >= 50) throw e;
                Thread.sleep(100);
            }
        }
    }

    @Test
    void checkoutNeedsALiveHold() {
        Long u = user();
        Setup s = heldSeats(u);
        s.seatIds().forEach(id -> redis.delete("hold:seat:" + id));
        assertThatThrownBy(() -> payments.checkout(u, s.eventId(), s.seatIds(), null)).isInstanceOf(SeatHoldService.HoldExpiredException.class);
    }
}
