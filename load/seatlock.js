// k6 load test for the Seatlock API.
//
// Two scenarios run together:
//   browse      400 req/s of public reads (event list + a seat map) for 30 s
//   contention  100 customers race for the same 20 seats (hold, then book), all at once
// teardown() then proves no seat was sold twice.
//
// Run (Docker, no install):
//   docker run --rm -i -v "${PWD}/load:/scripts" \
//     -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     grafana/k6 run /scripts/seatlock.js
//
// It creates one test event and 100 test users (loadtest-*@test.local). Point it at a local or
// staging API, never production.
import http from "k6/http";
import { check, fail } from "k6";
import exec from "k6/execution";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const CUSTOMERS = 100;
const SEATS = 20;
const JSON_HEADERS = { "Content-Type": "application/json" };

const won = new Counter("seat_bookings_won");
const lost = new Counter("seat_bookings_lost_409");

export const options = {
  scenarios: {
    browse: {
      executor: "constant-arrival-rate", exec: "browse",
      rate: 200, timeUnit: "1s", duration: "30s", preAllocatedVUs: 50, maxVUs: 300,
    },
    contention: {
      executor: "per-vu-iterations", exec: "grabSeat",
      vus: CUSTOMERS, iterations: 1, startTime: "10s", maxDuration: "60s",
    },
  },
  thresholds: {
    "http_req_duration{scenario:browse}": ["p(95)<250"],
    "http_req_failed{scenario:browse}": ["rate<0.01"],
    "checks{check:no_server_errors}": ["rate==1"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const auth = (token) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });

export function setup() {
  const login = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }), { headers: JSON_HEADERS });
  if (login.status !== 200) fail(`admin login failed (${login.status}); set ADMIN_EMAIL and ADMIN_PASSWORD`);
  const admin = login.json("token");

  const startsAt = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString();
  const event = http.post(`${BASE}/api/events`, JSON.stringify({
    title: `Load test ${Date.now()}`, venue: "k6 Arena", category: "Concert", startsAt,
    priceCents: 10000, rows: 1, seatsPerRow: SEATS, description: "Created by load/seatlock.js",
  }), auth(admin)).json();
  const seatIds = http.get(`${BASE}/api/events/${event.id}/seats`).json().map((s) => s.id);

  const run = Date.now();
  const tokens = [];
  for (let i = 0; i < CUSTOMERS; i++) {
    const r = http.post(`${BASE}/api/auth/register`, JSON.stringify({
      email: `loadtest-${run}-${i}@test.local`, name: `Load ${i}`, password: "load-test-pass",
    }), { headers: JSON_HEADERS });
    if (r.status !== 201) fail(`register failed: ${r.status} ${r.body}`);
    tokens.push(r.json("token"));
  }
  const browseEventId = http.get(`${BASE}/api/events`).json()[0].id;
  return { eventId: event.id, seatIds, tokens, browseEventId };
}

export function browse(data) {
  const list = http.get(`${BASE}/api/events`, { tags: { name: "GET /api/events" } });
  const seats = http.get(`${BASE}/api/events/${data.browseEventId}/seats`, { tags: { name: "GET /api/events/:id/seats" } });
  check(list, { "events 200": (r) => r.status === 200 });
  check(seats, { "seats 200": (r) => r.status === 200 });
  check(null, { no_server_errors: () => list.status < 500 && seats.status < 500 });
}

export function grabSeat(data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[i % data.tokens.length];
  const seatId = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const body = JSON.stringify({ eventId: data.eventId, seatIds: [seatId] });

  const hold = http.post(`${BASE}/api/holds`, body, { ...auth(token), tags: { name: "POST /api/holds" } });
  let outcome = hold;
  if (hold.status === 200) {
    outcome = http.post(`${BASE}/api/bookings`, body, {
      headers: { ...auth(token).headers, "Idempotency-Key": `load-${i}` }, tags: { name: "POST /api/bookings" },
    });
  }
  if (outcome.status === 201) won.add(1);
  else if (outcome.status === 409) lost.add(1);
  check(null, { no_server_errors: () => hold.status < 500 && outcome.status < 500 });
  check(outcome, { "won (201) or cleanly lost (409)": (r) => r.status === 201 || r.status === 409 });
}

/** The point of the whole system: every sold seat belongs to exactly one booking. */
export function teardown(data) {
  const sold = http.get(`${BASE}/api/events/${data.eventId}/seats`).json().filter((s) => s.status === "BOOKED").length;
  const owned = [];
  for (const t of data.tokens) {
    for (const b of http.get(`${BASE}/api/bookings/me`, auth(t)).json()) {
      if (b.eventId === data.eventId && b.status === "CONFIRMED") owned.push(...b.seats);
    }
  }
  const unique = new Set(owned).size;
  console.log(`seats sold: ${sold} / ${data.seatIds.length}; seats across all bookings: ${owned.length}; unique: ${unique}`);
  check(null, {
    "no seat sold twice": () => owned.length === unique,
    "every sold seat has exactly one booking": () => owned.length === sold,
  });
}
