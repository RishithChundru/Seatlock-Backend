package com.seatlock.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * The three Razorpay calls Seatlock needs (create order, refund, signature checks), over plain REST.
 * Enabled only when RAZORPAY_KEY_ID is set; otherwise checkout books instantly, as before payments existed.
 */
@Component
public class RazorpayClient {
    public static class ProviderException extends RuntimeException {
        public ProviderException(String message, Throwable cause) { super(message, cause); }
    }

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RestClient http;

    public RazorpayClient(@Value("${app.payments.razorpay.key-id}") String keyId,
                          @Value("${app.payments.razorpay.key-secret}") String keySecret,
                          @Value("${app.payments.razorpay.webhook-secret}") String webhookSecret,
                          @Value("${app.payments.razorpay.api-url}") String apiUrl) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        if (!keyId.isBlank() && keySecret.isBlank()) throw new IllegalStateException("RAZORPAY_KEY_SECRET is required when RAZORPAY_KEY_ID is set");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().baseUrl(apiUrl).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
    }

    public boolean enabled() { return !keyId.isBlank(); }

    /** Public key id, sent to the browser to open Checkout. The secret never leaves the server. */
    public String keyId() { return keyId; }

    /** Returns the Razorpay order id. amount is in paise. */
    public String createOrder(int amountPaise, String receipt) {
        try {
            Map<?, ?> res = http.post().uri("/v1/orders")
                    .body(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt))
                    .retrieve().body(Map.class);
            return (String) res.get("id");
        } catch (RestClientException e) {
            throw new ProviderException("Payments are unavailable right now. Please try again in a moment.", e);
        }
    }

    public void refund(String paymentId, int amountPaise) {
        try {
            http.post().uri("/v1/payments/{id}/refund", paymentId).body(Map.of("amount", amountPaise)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProviderException("Refund failed", e);
        }
    }

    /** Checkout's success callback signs "order_id|payment_id" with the key secret. */
    public boolean validPaymentSignature(String orderId, String paymentId, String signature) {
        return matches(keySecret, orderId + "|" + paymentId, signature);
    }

    /** Webhooks sign the raw request body with the webhook secret (set in the Razorpay dashboard). */
    public boolean validWebhookSignature(String body, String signature) {
        return !webhookSecret.isBlank() && matches(webhookSecret, body, signature);
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String secret, String data, String signature) {
        if (secret.isBlank() || signature == null) return false;
        return MessageDigest.isEqual(hmacHex(secret, data).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)); // constant time
    }
}
