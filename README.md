# SeatLock: concurrency-safe seat booking

Spring Boot 4 backend (PostgreSQL, Redis, RabbitMQ) with a Next.js 16 frontend for booking event seats. **Two people can never book the same seat**, even when they tap it in the same millisecond.

```
                                  ┌──────────── Redis ─────────────┐
                                  │ hold:seat:{id}  SET NX EX 300  │  checkout holds
                                  │ events::*       TTL 60s        │  listing cache
                                  └───────────────▲────────────────┘
Next.js ──REST + JWT──▶ Spring Boot ──────────────┤
 live seat map                │                   ▼
 hold countdown               │             PostgreSQL  (@Version optimistic lock = final guarantee)
 Idempotency-Key              │ after commit
                              ▼
                RabbitMQ  booking.confirmed ──▶ TicketIssuer (idempotent consumer)
                              │ 3 failed attempts
                              ▼
                          booking.confirmed.dlq
```

## How double-booking is prevented (two layers)

**1. Redis checkout holds (UX layer).** Tapping a seat runs `SET hold:seat:{id} {userId} NX EX 300`. `NX` means exactly one user wins the seat; `EX` makes abandoned holds expire on their own without a cleanup job. Other users see the seat as "in someone's cart". A user can hold at most 10 seats per event, multi-seat holds are all-or-nothing, and a seat is released only by its owner (a compare-and-delete Lua script).

**2. Optimistic locking in Postgres (correctness layer).** `Seat` has a `@Version` column. Booking flushes `UPDATE seats ... WHERE id = ? AND version = ?`. If another transaction got there first (for example a hold expired mid-checkout), 0 rows update, Hibernate throws `ObjectOptimisticLockingFailureException`, the whole transaction rolls back and the API returns **409 Conflict**. Even if Redis is wrong or down, the database never double-books.

**Idempotency:** clients send an `Idempotency-Key` header. A retry with the same key (double-click, flaky network) returns the original booking instead of creating a second one. This is enforced by a unique `(user_id, idempotency_key)` constraint.

## Async ticketing with RabbitMQ

After the booking transaction **commits** (`@TransactionalEventListener`), the service evicts the cache, releases the holds and publishes `BookingConfirmed` to the `booking.confirmed` queue. `TicketIssuer` consumes it, generates a ticket code and "emails" it (logged).

- **Retries + DLQ:** 3 attempts with backoff, then the message is dead-lettered to `booking.confirmed.dlq` instead of being retried forever.
- **Idempotent consumer:** a redelivered message for an already-ticketed booking is a no-op.
- **No lost messages:** if RabbitMQ is down at commit time, a scheduled sweeper re-publishes bookings that still have no ticket after 1 minute.

## Event cover images

Admins upload covers as multipart. The server **sniffs magic bytes** and accepts only real JPEG, PNG or WebP, ignoring the client's `Content-Type`, so an SVG or HTML file renamed to `.png` is rejected (no stored-XSS through uploads). Files are capped at 2 MB (413 above that). Bytes live in their own `event_images` table, so listing events never loads image data. `imageUrl` carries a version (`?v=<updated-at>`), so responses are served `Cache-Control: immutable, max-age=1y` and a new upload changes the URL.

## Tests (real infrastructure)

[`BookingConcurrencyTest`](src/test/java/com/seatlock/BookingConcurrencyTest.java) runs against real Postgres, Redis and RabbitMQ containers (Testcontainers):

| Test | Result |
|---|---|
| 50 threads book the same seat at the same instant | exactly 1 booking, 49 rejected, losers' rows rolled back |
| 50 users hold the same seat at the same instant | exactly 1 holder |
| Book a seat someone else holds | rejected (409) |
| Retry with the same Idempotency-Key | same booking returned |
| Booking → RabbitMQ → ticket issued | ticket code appears asynchronously |

## Run it

Requires Java 21+ and Docker. Maven is not needed; the wrapper downloads it.

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

The API runs on http://localhost:8080 and the RabbitMQ UI on http://localhost:15672 (guest/guest). For the UI, run the SeatLock frontend repo and open http://localhost:3000. Demo logins (seeded on first run):

| Role  | Email               | Password    |
|-------|---------------------|-------------|
| User  | demo@seatlock.dev   | Demo@12345  |
| Admin | admin@seatlock.dev  | Admin@12345 |

**Tests:** `.\mvnw.cmd test` (needs Docker running)

Config via env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `RABBIT_HOST`, `RABBIT_USER`, `RABBIT_PASSWORD`, `JWT_SECRET` (32+ bytes), `CORS_ORIGIN`.

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/events`, `/api/events/{id}` (cached) | public |
| GET | `/api/events/{id}/seats` (`HELD` / `mine` reflect live holds) | public |
| POST | `/api/events` | ADMIN |
| POST | `/api/events/{id}/image` (multipart `file`, JPEG/PNG/WebP ≤ 2 MB) | ADMIN |
| GET | `/api/events/{id}/image?v=…` (immutable, cached 1 year) | public |
| POST | `/api/holds` `{eventId, seatIds}` → hold for 5 min | USER |
| DELETE | `/api/holds/{seatId}` | USER |
| POST | `/api/bookings` (`Idempotency-Key` header optional) | USER |
| GET | `/api/bookings/me` (includes `ticketCode` once issued) | USER |

## Roadmap

- k6 load test with published p95 numbers and cache hit/miss latency
- Flyway migrations, Swagger UI
- Booking cancellation
