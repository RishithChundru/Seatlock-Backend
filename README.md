# Seatlock API

Spring Boot backend for **Seatlock**, an event seat-booking system where **two people can never book the same seat**, even when they tap it in the same millisecond.

**Stack:** Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · PostgreSQL · Redis · RabbitMQ · Server-Sent Events · Testcontainers · k6 · Docker

Frontend (Next.js): [Seatlock-Frontend](https://github.com/RishithChundru/Seatlock-Frontend)

**Highlights**
- No double-booking: Redis `SET NX` holds plus `@Version` optimistic locking, proven by a 50-thread race test and a k6 run (100 users, 20 seats).
- Cancellation with a **waitlist**: freed seats are held for the next person in line through RabbitMQ before anyone else sees them.
- **Live seat maps** over SSE, fanned out across instances with Redis pub/sub.
- **Load-tested:** found and fixed a connection-pool deadlock and cache stampede. Browse p95 went from 27.85 s to 8.65 ms at ~400 req/s, with zero 5xx.

```
                                  ┌──────────────── Redis ─────────────────┐
                                  │ hold:seat:{id}  SET NX EX 300          │  checkout holds
                                  │ events::*, seatRows::*                 │  read caches (no DB on hot reads)
                                  │ seatlock:seat-updates (pub/sub)        │  live-update fan-out
                                  └───────────────▲────────────────────────┘
Next.js ──REST + JWT──▶ Spring Boot ──────────────┤
  ◀──── SSE stream ────       │                   ▼
 live seat map                │             PostgreSQL  (@Version optimistic lock = final guarantee)
 Idempotency-Key              │ after commit
                              ▼
          RabbitMQ  booking.confirmed ──▶ TicketIssuer      (idempotent; 3 tries → .dlq)
                    seats.released    ──▶ WaitlistConsumer  (holds freed seats for the line; 3 tries → .dlq)
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
| http://localhost:8025 | Mailpit inbox: every email the app sends locally lands here |

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
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | optional | Mailpit on `localhost:1025` | Outgoing email; see [DEPLOY.md](DEPLOY.md#email-optional) |
| `MAIL_FROM` | optional | `Seatlock <tickets@seatlock.local>` | Sender address |
| `FRONTEND_URL` | ✅ for email links | `http://localhost:3000` | Where links in emails point |
| `DB_POOL_SIZE` | optional | `10` | Max DB connections per instance (hot reads use Redis, so this backs writes) |
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

### Cancellation and waitlist (event-driven)

A customer can cancel their own booking until the event starts (full refund, simulated). The seats go back on sale, but **people on the waitlist get first claim**:

```
cancel commits ──▶ seats.released (RabbitMQ) ──▶ WaitlistConsumer
                                                  ├─ for each freed seat: hold it in Redis for the next person in line (FIFO)
                                                  ├─ "email" them (logged)
                                                  └─ then ping every open seat map over SSE
```

Seat maps are only told about the change *after* the offers are made, so a fast clicker can't snatch a seat ahead of the line. Each person gets one offer; if they don't book within 5 minutes the hold expires and the seat goes back on general sale. Booking any seat for an event takes you off its waitlist. Cancelling is owner-only (someone else's booking id returns 404, so ids can't be probed) and idempotent. `seats.released` has its own retry + DLQ like `booking.confirmed`.

### Live seat map (SSE + Redis pub/sub)

`GET /api/events/{id}/stream` is a Server-Sent Events stream. Any instance that changes a seat (hold, release, booking, cancellation, waitlist offer) publishes the event id on a Redis channel, and **every** instance relays it to its own subscribers, so updates work behind a load balancer with several API instances. The message is just a "changed" ping; clients refetch the map with their own token so "your hold" stays correct. A 20-second heartbeat keeps proxies from closing idle streams, and streams are closed at the start of shutdown so graceful shutdown doesn't wait on them (that stall was caught by the test suite). Hold expiries aren't pushed, so clients also refetch every 30 s and when their own hold timer runs out.

### Load testing: what it found and what fixed it

[`load/seatlock.js`](load/seatlock.js) (k6) runs two scenarios at once: **~400 req/s of public reads** (event list + seat map) for 30 s, while **100 customers race for the same 20 seats** (hold, then book). Its teardown checks that no seat belongs to two bookings.

The first run fell over. The server log showed `HikariPool - Connection is not available (total=5, active=5, waiting=200)`. Three fixes, each re-measured:

1. **Seat maps were hitting Postgres on every view.** The "which seats are sold" part now lives in Redis (`SeatRows`, evicted when a booking or cancellation commits), with live holds overlaid from Redis. A seat-map read touches no database connection.
2. **Cached reads were still `@Transactional`,** so a cache hit could still borrow a connection. Removed.
3. **Pool deadlock in the booking path:** the after-commit step opened a *second* transaction while the committing one still held its connection, so concurrent bookings waited on each other. The booking event now carries what that step needs (email, title, seat labels) and it does no DB work. Cached reads also use `sync = true`, so an eviction triggers one rebuild instead of a stampede.

Plus fail-fast behavior: wait at most 5 s for a connection, then return **503 + `Retry-After`** instead of hanging for 30 s.

| Same test (local machine, everything in Docker) | Before | After |
|---|---|---|
| Browse p95 at ~400 req/s | 27.85 s | **8.65 ms** |
| Browse p99 | 30 s (timeouts) | **47.9 ms** |
| Server errors (5xx) | 18 | **0** |
| Browse request failures | 1.39% | **0%** |
| 100 users racing for 20 seats | 5 sold, the rest mostly errors | **19 sold, 81 clean 409s** |
| Any seat sold twice | no | **no** |

Run it yourself (no install needed, uses Docker). It creates one test event and 100 `loadtest-*@test.local` users, so point it at a local or staging API, never production:

```bash
docker run --rm -i -v "$PWD/load:/scripts" \
  -e BASE_URL=http://host.docker.internal:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
  grafana/k6 run /scripts/seatlock.js
```

### Seat tiers, search, check-in, email

- **Tiers:** events can have VIP and Premium front rows (default prices 2× and 1.5× the standard price, or set your own). Each seat stores its tier, and the event stores the prices. Booking totals are summed per seat, and seat maps show tier and price, served from the same Redis cache as the rest of the map.
- **Search:** `GET /api/events/search` filters upcoming events by text, category, city (parsed from "Place, City"), date window and max price, with pagination and the available cities and categories for the filter UI. It runs in memory over the cached event list. That's fine for hundreds of events, and the code notes when to move it to SQL.
- **Gate check-in:** tickets carry a QR of their code. `POST /api/admin/checkin` admits a ticket with **one conditional `UPDATE … WHERE checked_in_at IS NULL`**, so 20 scanners hitting the same ticket at once admit it exactly once (tested). A repeat scan reports when it was first used. Wrong-event and cancelled tickets are refused.
- **Email:** ticket issued, waitlist seat held and booking cancelled emails go out over SMTP (Mailpit locally, any provider such as Resend in production). Mail is **never sent inside a DB transaction**: the k6 work showed how holding a connection during slow I/O starves the pool. So mail goes out after commit, and the cancellation email is `@Async`. It's best-effort: an SMTP outage is logged and never blocks a booking.
- **Admin:** delete events (refused while confirmed bookings exist), remove covers. **Account:** change password (needs the current one).

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

[`CancelAndWaitlistTest`](src/test/java/com/seatlock/CancelAndWaitlistTest.java):

| Test | Result |
|---|---|
| Sold-out event, 2 people waiting, booking cancelled | freed seats held for #1 and #2 in order (via RabbitMQ); nobody else can book them; the offered person can, and leaves the waitlist |
| Cancel someone else's booking / cancel twice | 404 / same cancelled booking (idempotent) |
| Cancel after the event started | rejected |
| Join a waitlist while seats are free | rejected (409) |

[`SeatStreamTest`](src/test/java/com/seatlock/SeatStreamTest.java) opens a real SSE connection and checks a hold made elsewhere is pushed to it through Redis pub/sub. [`ImageSniffTest`](src/test/java/com/seatlock/service/ImageSniffTest.java) checks that only real image files pass the upload check.

[`Tier2FeaturesTest`](src/test/java/com/seatlock/Tier2FeaturesTest.java): tier pricing and per-seat totals; search with every filter plus pagination; **20 concurrent check-ins of one ticket → exactly one admission**; check-in refused for other events and cancelled bookings; delete refused while bookings exist; and a real ticket email arriving in a Mailpit container's inbox. Every test class creates its own events, because classes share one database and CI runs them in arbitrary order (a real CI failure that came from that).

---

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/events`, `/api/events/{id}` (cached; include `city` and `tiers`) | public |
| GET | `/api/events/search?q&category&city&from&to&maxPrice&page&size` | public |
| DELETE | `/api/events/{id}` (refused with 409 while confirmed bookings exist), `/api/events/{id}/image` | ADMIN |
| POST | `/api/admin/checkin` `{code, eventId?}` → `ADMITTED` / `ALREADY_CHECKED_IN` | ADMIN |
| GET | `/api/account/me`; POST `/api/account/password` `{currentPassword, newPassword}` | USER |
| GET | `/api/events/{id}/seats` (`HELD` / `mine` reflect live holds) | public |
| POST | `/api/events` | ADMIN |
| POST | `/api/events/{id}/image` (multipart `file`, JPEG/PNG/WebP ≤ 2 MB) | ADMIN |
| GET | `/api/events/{id}/image?v=…` (immutable, cached 1 year) | public |
| POST | `/api/holds` `{eventId, seatIds}` → hold for 5 min | USER |
| DELETE | `/api/holds/{seatId}` | USER |
| GET | `/api/events/{id}/stream` (Server-Sent Events: `seats` on every change) | public |
| POST | `/api/bookings` (`Idempotency-Key` header optional) | USER |
| GET | `/api/bookings/me` (includes `ticketCode` once issued, `status`) | USER |
| POST | `/api/bookings/{id}/cancel` (owner only, before the event starts) | USER |
| GET / POST / DELETE | `/api/events/{id}/waitlist` (your status / join a sold-out event / leave) | USER |
| GET | `/api/waitlist/me` (your waitlists, with any seat offered to you) | USER |

Authenticated calls send `Authorization: Bearer <token>` from the login/register response. Errors are RFC 7807 `ProblemDetail` JSON.

## Project structure

```
src/main/java/com/seatlock/
├── api/          Controllers, DTOs, error handling
├── config/       Security (JWT, CORS), sample-data seeder
├── domain/       JPA entities and repositories
├── messaging/    RabbitMQ config, publishers, ticket + waitlist consumers
├── realtime/     SSE streams fanned out over Redis pub/sub
└── service/      Events, bookings, cancellation, waitlist, Redis seat holds and seat-map cache
load/             k6 load test
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

- Push hold expiries too (Redis keyspace notifications), instead of the 30 s backup refetch
- Rate limiting on login and holds (Redis token bucket)
- Flyway migrations, Swagger UI
- Revoke existing logins on password change (token version claim)
