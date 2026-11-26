# Seatlock API

Spring Boot backend for **Seatlock**, an event seat-booking system where **two people can never book the same seat**, even when they tap it in the same millisecond.

**Stack:** Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · PostgreSQL · Redis · RabbitMQ · Testcontainers · Docker

Frontend (Next.js): [Seatlock-Frontend](https://github.com/RishithChundru/Seatlock-Frontend)

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

---

## Getting started

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 21 or newer | `java -version` |
| Docker Desktop | running | `docker info` |
| Git | any | `git --version` |

You don't need Maven; the included wrapper (`mvnw`) downloads it on first run.

### 1. Clone

```bash
git clone https://github.com/RishithChundru/Seatlock-Backend.git
cd Seatlock-Backend
```

### 2. Configure

Copy the example env file and set your admin login:

```bash
cp .env.example .env          # Windows PowerShell: Copy-Item .env.example .env
```

Open `.env` and set at least:

```properties
ADMIN_EMAIL=you@example.com
ADMIN_PASSWORD=choose-a-password-of-8+-chars
```

Everything else already points at the local Docker services. `.env` is git-ignored and loaded automatically when you run the app from this folder. If you leave `ADMIN_PASSWORD` empty, a random password is generated and printed once in the startup log.

### 3. Start Postgres, Redis and RabbitMQ

```bash
docker compose up -d
```

### 4. Run the API

```bash
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

On the first start with an empty database, the app creates the tables, four sample events with seat maps, and your admin account from `ADMIN_EMAIL` and `ADMIN_PASSWORD`.

| URL | What |
|---|---|
| http://localhost:8080/actuator/health | should return `{"status":"UP"}` |
| http://localhost:8080/api/events | sample events (JSON) |
| http://localhost:15672 | RabbitMQ management UI (local default login `guest` / `guest`) |

### 5. Run the frontend

Follow the [Seatlock-Frontend](https://github.com/RishithChundru/Seatlock-Frontend) README, then open http://localhost:3000.
- **Customers:** click **Sign in → Create an account**.
- **Admin:** sign in with your `ADMIN_EMAIL` and `ADMIN_PASSWORD` to reach the **Admin** page, where you create events and upload cover photos.

### 6. Run the tests

Docker must be running; the tests start their own throwaway Postgres, Redis and RabbitMQ containers.

```bash
./mvnw test                   # Windows: .\mvnw.cmd test
```

---

## Configuration

All settings are environment variables. Locally they come from `.env`; in production, from your host's secret settings. See [`.env.example`](.env.example).

| Variable | Required in prod | Local default | Purpose |
|---|---|---|---|
| `DB_URL` | ✅ | `jdbc:postgresql://localhost:5432/seatlock` | JDBC URL (`?sslmode=require` for hosted DBs) |
| `DB_USER` / `DB_PASSWORD` | ✅ | match `docker-compose.yml` | Database login |
| `REDIS_URL` | ✅ | `redis://localhost:6379` | Use `rediss://` for TLS (e.g. Upstash) |
| `RABBITMQ_URL` | ✅ | `amqp://…@localhost:5672` | Use `amqps://` for TLS (e.g. CloudAMQP) |
| `JWT_SECRET` | ✅ | dev-only value | 32+ random bytes: `openssl rand -base64 48` |
| `ADMIN_EMAIL` | ✅ | `admin@seatlock.local` | Admin account created on first start |
| `ADMIN_PASSWORD` | ✅ | *(generated, printed in log)* | 8+ characters |
| `CORS_ORIGINS` | ✅ | `http://localhost:3000` | Frontend URLs allowed to call the API, comma-separated |
| `PORT` | set by host | `8080` | HTTP port |

The admin account is only created on an **empty** database. Changing `ADMIN_EMAIL` or `ADMIN_PASSWORD` later doesn't change an existing account.

---

## How it works

### Double-booking is prevented in two layers

**1. Redis checkout holds (UX layer).** Tapping a seat runs `SET hold:seat:{id} {userId} NX EX 300`. `NX` means exactly one user wins the seat; `EX` makes abandoned holds expire on their own without a cleanup job. Other users see the seat as "in someone's cart". A user can hold at most 10 seats per event, multi-seat holds are all-or-nothing, and a seat is released only by its owner (a compare-and-delete Lua script).

**2. Optimistic locking in Postgres (correctness layer).** `Seat` has a `@Version` column. Booking flushes `UPDATE seats ... WHERE id = ? AND version = ?`. If another transaction got there first, 0 rows update, Hibernate throws `ObjectOptimisticLockingFailureException`, the whole transaction rolls back and the API returns **409 Conflict**. Even if Redis is wrong or down, the database never double-books.

**Idempotency:** clients send an `Idempotency-Key` header. A retry with the same key (double-click, flaky network) returns the original booking instead of creating a second one, enforced by a unique `(user_id, idempotency_key)` constraint.

### Async ticketing with RabbitMQ

After the booking transaction **commits** (`@TransactionalEventListener`), the service evicts the cache, releases the holds and publishes `BookingConfirmed` to `booking.confirmed`. `TicketIssuer` consumes it, generates a ticket code and "emails" it (logged).

- **Retries + DLQ:** 3 attempts with backoff, then the message is dead-lettered to `booking.confirmed.dlq`.
- **Idempotent consumer:** a redelivered message for an already-ticketed booking is a no-op.
- **No lost messages:** if RabbitMQ is down at commit time, a scheduled sweeper re-publishes bookings with no ticket after 1 minute.

### Event cover images

Admins upload covers as multipart. The server **sniffs magic bytes** and accepts only real JPEG, PNG or WebP, ignoring the client's `Content-Type`, so an SVG or HTML file renamed to `.png` is rejected (no stored XSS through uploads). Files are capped at 2 MB (413 above that). Bytes live in their own `event_images` table, so listing events never loads image data. `imageUrl` is versioned (`?v=<updated-at>`), so it's served `Cache-Control: immutable, max-age=1y`.

### Tests (real infrastructure)

[`BookingConcurrencyTest`](src/test/java/com/seatlock/BookingConcurrencyTest.java) runs against real Postgres, Redis and RabbitMQ containers:

| Test | Result |
|---|---|
| 50 threads book the same seat at the same instant | exactly 1 booking, 49 rejected, losers' rows rolled back |
| 50 users hold the same seat at the same instant | exactly 1 holder |
| Book a seat someone else holds | rejected (409) |
| Retry with the same Idempotency-Key | same booking returned |
| Booking → RabbitMQ → ticket issued | ticket code appears asynchronously |

[`ImageSniffTest`](src/test/java/com/seatlock/service/ImageSniffTest.java) checks that only real image files pass the upload check.

---

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

Authenticated calls send `Authorization: Bearer <token>` from the login/register response. Errors are RFC 7807 `ProblemDetail` JSON.

## Project structure

```
src/main/java/com/seatlock/
├── api/          Controllers, DTOs, error handling
├── config/       Security (JWT, CORS), sample-data seeder
├── domain/       JPA entities and repositories
├── messaging/    RabbitMQ config, publisher, ticket consumer
└── service/      Events, bookings, Redis seat holds
```

---

## Branches and CI/CD

- Work on **`dev`**; merge to **`main`** through a pull request.
- GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the tests and a Docker build on every push to `dev`/`main` and on PRs.
- Pushes to `main` deploy to Render only if both pass.

## Deploy

Neon (Postgres) + Upstash (Redis) + CloudAMQP (RabbitMQ) + Render (API, Docker) + Vercel (frontend), all with free tiers. Step-by-step: **[DEPLOY.md](DEPLOY.md)**.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Connection refused` to 5432 / 6379 / 5672 on start | `docker compose up -d`, then check `docker compose ps` |
| Tests fail with "Could not find a valid Docker environment" | Start Docker Desktop |
| Can't sign in as admin | The account is created only on an empty database. Check the startup log for a generated password, or reset local data with `docker compose down -v` and start again |
| Frontend shows "Can't reach the server" | API not running, or the frontend URL is missing from `CORS_ORIGINS` |
| Port 8080 already in use | Set `PORT=8081` in `.env` and point the frontend's `NEXT_PUBLIC_API_URL` at it |

## Roadmap

- k6 load test with published p95 numbers and cache hit/miss latency
- Flyway migrations, Swagger UI
- Booking cancellation, remove cover image
