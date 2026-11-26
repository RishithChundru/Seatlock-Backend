package com.seatlock.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics, scraped at /actuator/prometheus next to the built-in HTTP, JVM and Hikari pool metrics.
 * The conflict rate (bookings and holds that lost a race) is the number that shows the locking working.
 */
@Component
public class SeatlockMetrics {
    private final MeterRegistry registry;
    private final Counter bookingsConfirmed, bookingConflicts, cancellations, holdsPlaced, holdConflicts, waitlistOffers;

    public SeatlockMetrics(MeterRegistry registry) {
        this.registry = registry;
        bookingsConfirmed = Counter.builder("seatlock.bookings").tag("outcome", "confirmed").description("Bookings committed").register(registry);
        bookingConflicts = Counter.builder("seatlock.bookings").tag("outcome", "conflict").description("Bookings rejected because a seat was taken").register(registry);
        cancellations = Counter.builder("seatlock.cancellations").description("Bookings cancelled").register(registry);
        holdsPlaced = Counter.builder("seatlock.holds").tag("outcome", "held").register(registry);
        holdConflicts = Counter.builder("seatlock.holds").tag("outcome", "conflict").register(registry);
        waitlistOffers = Counter.builder("seatlock.waitlist.offers").description("Freed seats held for people in line").register(registry);
    }

    public void bookingConfirmed() { bookingsConfirmed.increment(); }
    public void bookingConflict() { bookingConflicts.increment(); }
    public void cancelled() { cancellations.increment(); }
    public void holdPlaced() { holdsPlaced.increment(); }
    public void holdConflict() { holdConflicts.increment(); }
    public void waitlistOffers(int n) { waitlistOffers.increment(n); }
    public void checkIn(String result) { registry.counter("seatlock.checkins", "result", result).increment(); }
}
