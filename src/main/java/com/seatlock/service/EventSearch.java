package com.seatlock.service;

import com.seatlock.api.Dtos.EventDto;
import com.seatlock.api.Dtos.EventPage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Filtered, paginated upcoming events. Its own bean so list() goes through EventService's cache proxy
 * (a call from inside EventService would skip the cache and hit the database on every search).
 * shortcut: filters run in memory over the cached list; fine for hundreds of events, move to a SQL query beyond that
 */
@Service
public class EventSearch {
    private final EventService events;

    public EventSearch(EventService events) { this.events = events; }

    public EventPage search(String q, String category, String city, Instant from, Instant to, Integer maxPriceCents,
                            int page, int size) {
        Instant now = Instant.now();
        List<EventDto> upcoming = events.list().stream().filter(e -> e.startsAt().isAfter(now)).toList();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<EventDto> hits = upcoming.stream()
                .filter(e -> needle.isEmpty() || (e.title() + " " + e.venue() + " " + e.category()).toLowerCase(Locale.ROOT).contains(needle))
                .filter(e -> blank(category) || e.category().equalsIgnoreCase(category))
                .filter(e -> blank(city) || e.city().equalsIgnoreCase(city))
                .filter(e -> from == null || !e.startsAt().isBefore(from))
                .filter(e -> to == null || e.startsAt().isBefore(to))
                .filter(e -> maxPriceCents == null || e.priceCents() <= maxPriceCents)
                .toList();
        int start = Math.min(page * size, hits.size());
        return new EventPage(hits.subList(start, Math.min(start + size, hits.size())), hits.size(), page, size,
                upcoming.stream().map(EventDto::city).distinct().sorted().toList(),
                upcoming.stream().map(EventDto::category).distinct().sorted().toList());
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
