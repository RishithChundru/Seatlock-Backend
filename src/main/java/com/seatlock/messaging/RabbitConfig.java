package com.seatlock.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * booking.confirmed  ──(3 failed attempts)──▶  booking.confirmed.dlq
 * Failed messages are parked in the DLQ for inspection instead of being retried forever.
 */
@Configuration
public class RabbitConfig {
    public static final String QUEUE = "booking.confirmed";
    public static final String DLQ = "booking.confirmed.dlq";

    @Bean
    Queue bookingConfirmedQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DLQ).build();
    }

    @Bean
    Queue bookingConfirmedDlq() { return QueueBuilder.durable(DLQ).build(); }

    @Bean
    MessageConverter jsonMessageConverter() { return new JacksonJsonMessageConverter(); }
}
