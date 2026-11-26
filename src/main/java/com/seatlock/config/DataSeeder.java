package com.seatlock.config;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.api.Dtos.EventDto;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.*;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Demo data on an empty database. Test logins: admin@seatlock.dev / Admin@12345, demo@seatlock.dev / Demo@12345 */
@Component
class DataSeeder implements CommandLineRunner {
    private final UserRepository users;
    private final EventRepository eventRepo;
    private final SeatRepository seats;
    private final EventService events;
    private final BookingService bookings;
    private final PasswordEncoder encoder;

    DataSeeder(UserRepository users, EventRepository eventRepo, SeatRepository seats, EventService events,
               BookingService bookings, PasswordEncoder encoder) {
        this.users = users;
        this.eventRepo = eventRepo;
        this.seats = seats;
        this.events = events;
        this.bookings = bookings;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (eventRepo.count() > 0) return;
        AppUser admin = users.save(new AppUser("admin@seatlock.dev", "Admin", encoder.encode("Admin@12345"), AppUser.Role.ADMIN));
        users.save(new AppUser("demo@seatlock.dev", "Demo User", encoder.encode("Demo@12345"), AppUser.Role.USER));

        Instant day = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(14)); // 19:30 IST
        List<EventDto> created = List.of(
                events.create(new CreateEventRequest("Neon Nights: Synthwave Live", "Skyline Arena, Hyderabad", "Concert", day.plus(Duration.ofDays(6)), 149900, 10, 16)),
                events.create(new CreateEventRequest("Late Night Laughs", "The Comedy Cellar, Bengaluru", "Comedy", day.plus(Duration.ofDays(9)), 79900, 8, 12)),
                events.create(new CreateEventRequest("Monsoon Stories: Film Premiere", "Lumière Screens, Mumbai", "Movie", day.plus(Duration.ofDays(13)), 39900, 9, 14)),
                events.create(new CreateEventRequest("SystemConf 2026: Scaling Talks", "Hitex Hall 3, Hyderabad", "Tech", day.plus(Duration.ofDays(20)), 199900, 8, 14)));

        // Pre-book a scattered handful so the seat maps look lived-in.
        for (EventDto e : created) {
            var ids = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(e.id()).stream()
                    .filter(s -> (s.getId() * 7) % 5 == 0).limit(e.totalSeats() / 4).map(s -> s.getId()).toList();
            bookings.book(admin.getId(), e.id(), ids.subList(0, Math.min(10, ids.size())), null);
            if (ids.size() > 10) bookings.book(admin.getId(), e.id(), ids.subList(10, Math.min(20, ids.size())), null);
        }
    }
}
