# SeatLock: concurrency-safe seat booking

Spring Boot 4 + PostgreSQL backend and Next.js 16 frontend for booking event seats. **Two people can never book the same seat**, even when they tap it in the same millisecond.

```
Next.js (React 19, Tailwind)  ──REST + JWT──▶  Spring Boot (Web MVC, Security, JPA)  ──▶  PostgreSQL
  live seat map (4s refresh)                    optimistic locking (@Version)
  Idempotency-Key per checkout                  idempotent bookings, ProblemDetail errors
```

## The hard problem: double-booking

`Seat` has a `@Version` column. Booking runs in one transaction:

1. Load the requested seats and reject any already `BOOKED` (fast path).
2. Insert the `Booking`, mark seats `BOOKED`, flush. Hibernate issues `UPDATE seats ... WHERE id = ? AND version = ?`.
3. If another transaction got there first, the version no longer matches, 0 rows update, and Hibernate throws `ObjectOptimisticLockingFailureException`. The whole transaction rolls back (including the booking row) and the API returns **409 Conflict**.

**Idempotency:** clients send an `Idempotency-Key` header. A retry with the same key (double-click, flaky network) returns the original booking instead of creating a second one. Enforced by a unique `(user_id, idempotency_key)` constraint.

**Proof:** [`BookingConcurrencyTest`](src/test/java/com/seatlock/BookingConcurrencyTest.java) fires 50 threads at the same seat simultaneously and asserts exactly 1 booking succeeds and 49 are rejected.

## Features

- JWT auth (HS256, Spring Security resource server), BCrypt passwords, `USER` / `ADMIN` roles
- Events, seat maps, bookings, "my tickets"; admins can create events (`POST /api/events`)
- Errors as RFC 7807 `ProblemDetail` JSON
- Next.js UI: event grid, interactive seat map with live refresh, booking confirmation, tickets page, mobile layout

## Run it

Requires Java 21+. Maven is not needed; the wrapper downloads it.

**With PostgreSQL (Docker):**

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

**Without Docker (H2 file DB):**

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"
```

The API runs on http://localhost:8080. For the UI, run the SeatLock frontend repo (Next.js) and open http://localhost:3000. Demo logins (seeded on first run):

| Role  | Email               | Password    |
|-------|---------------------|-------------|
| User  | demo@seatlock.dev   | Demo@12345  |
| Admin | admin@seatlock.dev  | Admin@12345 |

**Tests:** `.\mvnw.cmd test`

Config via env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (32+ bytes), `CORS_ORIGIN`, and `NEXT_PUBLIC_API_URL` for the frontend.

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/events`, `/api/events/{id}`, `/api/events/{id}/seats` | public |
| POST | `/api/events` | ADMIN |
| POST | `/api/bookings` (`Idempotency-Key` header optional) | USER |
| GET | `/api/bookings/me` | USER |

## Roadmap

- Redis seat holds (5-minute TTL while paying) and Redis cache for event listings
- RabbitMQ/Kafka `BookingConfirmed` event → email/ticket consumer
- Flyway migrations, Swagger UI, Testcontainers (Postgres) tests
- k6 load test with published p95 numbers
- Booking cancellation
