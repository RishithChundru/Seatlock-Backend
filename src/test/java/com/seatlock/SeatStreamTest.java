package com.seatlock;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.*;
import com.seatlock.domain.Seat;
import com.seatlock.service.SeatHoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.seatlock.api.Dtos.CreateEventRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP: a client on the SSE stream hears about a hold made elsewhere (through Redis pub/sub). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class SeatStreamTest {
    @LocalServerPort int port;
    @Autowired SeatHoldService holds;
    @Autowired EventRepository events;
    @Autowired SeatRepository seats;
    @Autowired UserRepository users;
    @Autowired com.seatlock.service.EventService eventService;

    @Test
    void streamPushesAnUpdateWhenASeatIsHeld() throws Exception {
        // Own event: test classes share a database and run in arbitrary order on CI.
        Long eventId = eventService.create(new CreateEventRequest("Stream " + UUID.randomUUID(), "Hall, Test City", "Concert",
                Instant.now().plus(Duration.ofDays(7)), 1000, 1, 2, null)).id();
        Seat seat = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).get(0);
        Long user = users.save(new AppUser("sse-" + UUID.randomUUID() + "@test.local", "Sse", "x", AppUser.Role.USER)).getId();

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + eventId + "/stream"))
                .header("Accept", "text/event-stream").build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(r -> r.body().forEach(lines::add));

        assertThat(waitForLine(lines, "event:ready")).as("stream opened").isTrue();
        holds.hold(user, eventId, List.of(seat.getId()));
        assertThat(waitForLine(lines, "event:seats")).as("update pushed after hold").isTrue();

        response.cancel(true);
        holds.release(user, seat.getId());
    }

    private static boolean waitForLine(BlockingQueue<String> lines, String wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.replace(" ", "").startsWith(wanted)) return true;
        }
        return false;
    }
}
