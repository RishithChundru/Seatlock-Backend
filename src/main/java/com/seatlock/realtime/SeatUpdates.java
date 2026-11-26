package com.seatlock.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live seat-map updates. Any instance that changes a seat publishes the event id on a Redis channel;
 * every instance relays it to its own SSE subscribers, so this works behind a load balancer.
 * The message is only a "changed" ping; clients refetch seats with their own token (keeps "mine" correct).
 */
@Component
public class SeatUpdates implements MessageListener {
    static final String CHANNEL = "seatlock:seat-updates";
    private static final Logger log = LoggerFactory.getLogger(SeatUpdates.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final StringRedisTemplate redis;
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SeatUpdates(StringRedisTemplate redis, io.micrometer.core.instrument.MeterRegistry registry) {
        this.redis = redis;
        // Open live seat-map streams on this instance.
        io.micrometer.core.instrument.Gauge.builder("seatlock.sse.subscribers", subscribers,
                m -> m.values().stream().mapToInt(java.util.Set::size).sum()).register(registry);
    }

    /** Call after a seat change is committed. Never throws: live updates are best-effort, clients also poll. */
    public void publish(Long eventId) {
        try {
            redis.convertAndSend(CHANNEL, eventId.toString());
        } catch (RuntimeException e) {
            log.warn("Could not publish seat update for event {}", eventId, e);
        }
    }

    public SseEmitter subscribe(Long eventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> set = subscribers.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(eventId, emitter, SseEmitter.event().name("ready").data("ok"));
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Long eventId;
        try {
            eventId = Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return;
        }
        Set<SseEmitter> set = subscribers.get(eventId);
        if (set != null) set.forEach(em -> send(eventId, em, SseEmitter.event().name("seats").data("changed")));
    }

    /** Comment line every 20s so proxies (Render, Vercel, nginx) don't close idle streams. */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        subscribers.forEach((eventId, set) -> set.forEach(em -> send(eventId, em, SseEmitter.event().comment("hb"))));
    }

    /**
     * Shutdown starts with this event, before graceful shutdown waits for in-flight requests. Open streams
     * never finish on their own, so end them here or every deploy stalls for the full grace period.
     * Browsers' EventSource reconnects automatically to the new instance.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
        subscribers.clear();
    }

    int subscriberCount(Long eventId) {
        Set<SseEmitter> set = subscribers.get(eventId);
        return set == null ? 0 : set.size();
    }

    private void send(Long eventId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            Set<SseEmitter> set = subscribers.get(eventId);
            if (set != null) set.remove(emitter); // client went away
        }
    }

    @Configuration
    static class RedisChannelConfig {
        @Bean
        RedisMessageListenerContainer seatUpdatesListener(RedisConnectionFactory factory, SeatUpdates updates) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener(updates, new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
