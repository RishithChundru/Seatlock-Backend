package com.seatlock;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.api.Dtos.EventDto;
import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
class Tier2FeaturesTest {
    @Autowired EventService events;
    @Autowired EventSearch search;
    @Autowired BookingService bookings;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookingRepo;
    @Autowired UserRepository users;
    @Value("${test.mailpit.api}") String mailpitApi;

    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private AppUser user(String name) {
        return users.save(new AppUser(name + "-" + UUID.randomUUID() + "@test.local", name, "x", AppUser.Role.USER));
    }

    private EventDto event(String title, String venue, String category, int priceCents, Instant startsAt,
                           int rows, Integer vipRows, Integer premiumRows) {
        return events.create(new CreateEventRequest(title + " " + run, venue, category, startsAt, priceCents, rows, 4, null,
                vipRows, null, premiumRows, null));
    }

    private List<Seat> seatsOf(Long eventId) { return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId); }

    private String ticketCode(Long bookingId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String code = bookingRepo.findById(bookingId).orElseThrow().getTicketCode();
            if (code != null) return code;
            Thread.sleep(100);
        }
        throw new AssertionError("ticket never issued");
    }

    @Test
    void tiersPriceFrontRowsHigherAndBookingsChargePerSeat() {
        EventDto ev = event("Tiers", "Hall, Pune", "Concert", 100000, Instant.now().plus(Duration.ofDays(5)), 3, 1, 1);
        assertThat(ev.tiers()).extracting(t -> t.tier() + ":" + t.priceCents())
                .containsExactly("VIP:200000", "PREMIUM:150000", "STANDARD:100000"); // defaults: 2x and 1.5x

        List<Seat> all = seatsOf(ev.id());
        assertThat(all).filteredOn(s -> s.getRowLabel().equals("A")).allMatch(s -> s.getTier() == Seat.Tier.VIP);
        assertThat(all).filteredOn(s -> s.getRowLabel().equals("B")).allMatch(s -> s.getTier() == Seat.Tier.PREMIUM);
        assertThat(all).filteredOn(s -> s.getRowLabel().equals("C")).allMatch(s -> s.getTier() == Seat.Tier.STANDARD);

        Seat vip = all.get(0), standard = all.get(all.size() - 1);
        var booking = bookings.book(user("tiers").getId(), ev.id(), List.of(vip.getId(), standard.getId()), null);
        assertThat(booking.totalCents()).isEqualTo(200000 + 100000);
        assertThat(events.seats(ev.id(), null).get(0).priceCents()).isEqualTo(200000);
    }

    @Test
    void tierRowsCantExceedTotalRows() {
        assertThatThrownBy(() -> event("Bad", "Hall, Pune", "Concert", 1000, Instant.now().plus(Duration.ofDays(5)), 2, 2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchFiltersByTextCityCategoryPriceDateAndPaginates() {
        Instant soon = Instant.now().plus(Duration.ofDays(2)), later = Instant.now().plus(Duration.ofDays(40));
        event("Jazz Night", "Blue Room, Kochi", "Concert", 50000, soon, 1, null, null);
        event("Jazz Brunch", "Cafe, Kochi", "Concert", 20000, later, 1, null, null);
        event("Stand-up Kochi", "Club, Kochi", "Comedy", 30000, soon, 1, null, null);
        event("Old Jazz", "Blue Room, Kochi", "Concert", 10000, Instant.now().minus(Duration.ofDays(1)), 1, null, null);

        var jazz = search.search("jazz " + run, null, null, null, null, null, 0, 10);
        assertThat(jazz.items()).isEmpty(); // whole phrase must match
        var kochiConcerts = search.search(run, "concert", "kochi", null, null, null, 0, 10);
        assertThat(kochiConcerts.items()).extracting(EventDto::title).allMatch(t -> t.contains("Jazz")).hasSize(2); // past one excluded
        assertThat(search.search(run, null, "Kochi", null, null, 25000, 0, 10).items()).hasSize(1);
        assertThat(search.search(run, null, "Kochi", null, Instant.now().plus(Duration.ofDays(10)), null, 0, 10).items()).hasSize(2);
        var page = search.search(run, null, "Kochi", null, null, null, 1, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(1);
        assertThat(page.cities()).contains("Kochi");
    }

    @Test
    void checkInAdmitsATicketExactlyOnceEvenWithManyScanners() throws Exception {
        EventDto ev = event("Gate", "Arena, Delhi", "Concert", 1000, Instant.now().plus(Duration.ofDays(5)), 1, null, null);
        var booking = bookings.book(user("fan").getId(), ev.id(), List.of(seatsOf(ev.id()).get(0).getId()), null);
        String code = ticketCode(booking.id());

        var pool = Executors.newFixedThreadPool(20);
        var start = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> {
            start.await();
            if (bookings.checkIn(code.toLowerCase(), ev.id()).result().equals("ADMITTED")) admitted.incrementAndGet();
            return null;
        }));
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(admitted.get()).isEqualTo(1);
        var again = bookings.checkIn(code, ev.id());
        assertThat(again.result()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(again.checkedInAt()).isNotNull();
    }

    @Test
    void checkInRefusesWrongEventAndCancelledBookings() throws Exception {
        EventDto ev = event("Gate2", "Arena, Delhi", "Concert", 1000, Instant.now().plus(Duration.ofDays(5)), 1, null, null);
        EventDto other = event("Other", "Arena, Delhi", "Concert", 1000, Instant.now().plus(Duration.ofDays(5)), 1, null, null);
        AppUser fan = user("fan");
        var booking = bookings.book(fan.getId(), ev.id(), List.of(seatsOf(ev.id()).get(0).getId()), null);
        String code = ticketCode(booking.id());

        assertThatThrownBy(() -> bookings.checkIn(code, other.id())).isInstanceOf(BookingService.CheckInRefusedException.class);
        bookings.cancel(fan.getId(), booking.id());
        assertThatThrownBy(() -> bookings.checkIn(code, ev.id())).isInstanceOf(BookingService.CheckInRefusedException.class);
    }

    @Test
    void deletingAnEventIsRefusedWhileItHasConfirmedBookings() {
        EventDto ev = event("Doomed", "Hall, Goa", "Comedy", 1000, Instant.now().plus(Duration.ofDays(5)), 1, null, null);
        AppUser fan = user("fan");
        var booking = bookings.book(fan.getId(), ev.id(), List.of(seatsOf(ev.id()).get(0).getId()), null);

        assertThatThrownBy(() -> events.delete(ev.id())).isInstanceOf(EventService.EventHasBookingsException.class);
        bookings.cancel(fan.getId(), booking.id());
        events.delete(ev.id());
        assertThat(seatsOf(ev.id())).isEmpty();
        assertThat(bookingRepo.findById(booking.id())).isEmpty();
    }

    @Test
    void ticketEmailArrivesInTheInbox() throws Exception {
        EventDto ev = event("Mail", "Hall, Goa", "Concert", 1000, Instant.now().plus(Duration.ofDays(5)), 1, null, null);
        AppUser fan = user("mailfan");
        var booking = bookings.book(fan.getId(), ev.id(), List.of(seatsOf(ev.id()).get(0).getId()), null);
        String code = ticketCode(booking.id());

        HttpClient http = HttpClient.newHttpClient();
        String query = java.net.URLEncoder.encode("to:\"" + fan.getEmail() + "\"", java.nio.charset.StandardCharsets.UTF_8);
        String found = "", body = "";
        for (int i = 0; i < 50 && !found.contains("\"ID\""); i++) {
            Thread.sleep(200); // the email goes out just after the ticket transaction commits
            found = http.send(HttpRequest.newBuilder(URI.create(mailpitApi + "/api/v1/search?query=" + query)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
        String id = found.replaceAll("(?s).*?\"ID\":\"([^\"]+)\".*", "$1");
        body = http.send(HttpRequest.newBuilder(URI.create(mailpitApi + "/api/v1/message/" + id)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(found).contains("Your tickets for Mail " + run);
        assertThat(body).contains(code).contains("/bookings");
    }
}
