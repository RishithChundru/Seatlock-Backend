package com.seatlock.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * booking.confirmed  ──(3 failed attempts)──▶  booking.confirmed.dlq   (ticket issuing)
 * seats.released     ──(3 failed attempts)──▶  seats.released.dlq      (waitlist offers after a cancellation)
 * Failed messages are parked in a DLQ for inspection instead of being retried forever.
 */
@Configuration
public class RabbitConfig {
    public static final String QUEUE = "booking.confirmed";
    public static final String DLQ = "booking.confirmed.dlq";
    public static final String SEATS_RELEASED = "seats.released";
    public static final String SEATS_RELEASED_DLQ = "seats.released.dlq";

    @Bean
    Queue bookingConfirmedQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DLQ).build();
    }

    @Bean
    Queue bookingConfirmedDlq() { return QueueBuilder.durable(DLQ).build(); }

    @Bean
    Queue seatsReleasedQueue() {
        return QueueBuilder.durable(SEATS_RELEASED).deadLetterExchange("").deadLetterRoutingKey(SEATS_RELEASED_DLQ).build();
    }

    @Bean
    Queue seatsReleasedDlq() { return QueueBuilder.durable(SEATS_RELEASED_DLQ).build(); }

    @Bean
    MessageConverter jsonMessageConverter() { return new JacksonJsonMessageConverter(); }
}
