package com.seatlock;

import com.seatlock.domain.AppUser;
import com.seatlock.domain.Repositories.UserRepository;
import com.seatlock.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cookie sessions, migrations, API docs and metrics, over real HTTP where it matters. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@AutoConfigureMetrics // Boot disables metric exporters in tests by default; we want /actuator/prometheus here
class SessionAndPlatformTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private AppUser user() {
        return users.save(new AppUser("s-" + UUID.randomUUID() + "@test.local", "Session Test", "x", AppUser.Role.USER));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    /** "name=value" of the Set-Cookie header for that cookie. */
    private static String cookie(HttpResponse<?> res, String name) {
        String h = res.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(name + "=")).findFirst().orElseThrow();
        assertThat(h).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        return h.substring(0, h.indexOf(';'));
    }

    @Test
    void bothTokensAreHttpOnlyCookiesThatAuthenticateRotateAndNeedTheCsrfHeader() throws Exception {
        String email = "c-" + UUID.randomUUID() + "@test.local";
        var reg = send(HttpRequest.newBuilder(URI.create(url("/api/auth/register"))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"name\":\"C\",\"password\":\"password-123\"}")));
        assertThat(reg.statusCode()).isEqualTo(201);
        String refresh = cookie(reg, "sl_refresh");
        String access = cookie(reg, "sl_access");

        // The access cookie alone authenticates the browser app (no Authorization header).
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/bookings/me"))).header("Cookie", access)).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/bookings/me")))).statusCode()).isEqualTo(401);

        // A cookie-carrying POST without X-Requested-With is refused (what a cross-site form post would look like).
        var forged = send(HttpRequest.newBuilder(URI.create(url("/api/auth/refresh"))).header("Cookie", refresh)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(forged.statusCode()).isEqualTo(403);

        var refreshed = send(HttpRequest.newBuilder(URI.create(url("/api/auth/refresh"))).header("Cookie", refresh + "; " + access)
                .header("X-Requested-With", "fetch").POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String rotated = cookie(refreshed, "sl_refresh");
        assertThat(rotated).isNotEqualTo(refresh);
        assertThat(cookie(refreshed, "sl_access")).startsWith("sl_access=ey");

        var session = send(HttpRequest.newBuilder(URI.create(url("/api/auth/session"))).header("Cookie", rotated));
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.body()).contains(email);

        // Logout clears both cookies.
        var out = send(HttpRequest.newBuilder(URI.create(url("/api/auth/logout"))).header("Cookie", rotated)
                .header("X-Requested-With", "fetch").POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(out.headers().allValues("Set-Cookie")).anyMatch(v -> v.startsWith("sl_refresh=;") && v.contains("Max-Age=0"))
                .anyMatch(v -> v.startsWith("sl_access=;") && v.contains("Max-Age=0"));

        var noCookie = send(HttpRequest.newBuilder(URI.create(url("/api/auth/refresh"))).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(noCookie.statusCode()).isEqualTo(401);
    }

    @Test
    void aStaleAccessCookieNeverBlocksLoginOrPublicReads() throws Exception {
        String stale = "sl_access=not-a-valid-jwt";
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/auth/session"))).header("Cookie", stale)).statusCode()).isEqualTo(401);
        // Bearer header wins over the cookie, so API clients are unaffected by whatever cookie is lying around.
        var u = user();
        String bearer = "Bearer " + auth.accessToken(u);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/bookings/me"))).header("Cookie", stale)
                .header("Authorization", bearer)).statusCode()).isEqualTo(200);
    }

    @Test
    void reusingARotatedTokenAfterTheGraceWindowRevokesTheWholeLogin() {
        AppUser u = user();
        var first = auth.start(u);
        var second = auth.rotate(first.refreshToken());

        // Two tabs refreshing at once: the stale token is refused, but the session survives.
        assertThatThrownBy(() -> auth.rotate(first.refreshToken())).hasMessageContaining("another tab");
        var third = auth.rotate(second.refreshToken());

        // The same stale token turning up long after rotation = it was copied. Everything in that login dies.
        jdbc.update("update refresh_tokens set revoked_at = now() - interval '5 minutes' where revoked_at is not null and user_id = ?", u.getId());
        assertThatThrownBy(() -> auth.rotate(first.refreshToken())).hasMessageContaining("security");
        assertThat(auth.peek(third.refreshToken())).isEmpty();
        assertThatThrownBy(() -> auth.rotate(third.refreshToken())).isInstanceOf(AuthService.InvalidRefreshException.class);
    }

    @Test
    void logoutEndsTheSessionAndPasswordChangeEndsOnlyOtherDevices() {
        AppUser u = user();
        var laptop = auth.start(u);
        var phone = auth.start(u);
        var tablet = auth.start(u);

        auth.endOtherSessions(u.getId(), laptop.refreshToken());
        assertThat(auth.peek(laptop.refreshToken())).isPresent();
        assertThat(auth.peek(phone.refreshToken())).isEmpty();
        assertThat(auth.peek(tablet.refreshToken())).isEmpty();

        auth.logout(laptop.refreshToken());
        assertThat(auth.peek(laptop.refreshToken())).isEmpty();
    }

    @Test
    void flywayAppliedEveryMigrationOnAFreshDatabase() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        var docs = send(HttpRequest.newBuilder(URI.create(url("/v3/api-docs"))));
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body()).contains("Seatlock API").contains("/api/bookings").contains("bearer");
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/swagger-ui.html")))).statusCode()).isIn(200, 302);
    }

    @Test
    void prometheusMetricsNeedTheScrapePassword() throws Exception {
        String good = "Basic " + Base64.getEncoder().encodeToString("prometheus:test-metrics-token".getBytes(StandardCharsets.UTF_8));
        String bad = "Basic " + Base64.getEncoder().encodeToString("prometheus:guess".getBytes(StandardCharsets.UTF_8));

        assertThat(send(HttpRequest.newBuilder(URI.create(url("/actuator/prometheus")))).statusCode()).isIn(401, 403);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/actuator/prometheus"))).header("Authorization", bad)).statusCode()).isIn(401, 403);
        var ok = send(HttpRequest.newBuilder(URI.create(url("/actuator/prometheus"))).header("Authorization", good));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("seatlock_bookings_total").contains("hikaricp_connections_active").contains("seatlock_sse_subscribers");
    }
}
