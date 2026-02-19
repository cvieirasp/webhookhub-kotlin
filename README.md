# WebhookHub

WebhookHub is a webhook ingestion and delivery system built on Kotlin JVM.
It receives signed HTTP webhook events, persists them, and delivers them asynchronously to configured HTTP destinations with automatic retries and a dead-letter queue for failed deliveries.

---

## Architecture

```
             ┌─────────────┐
HTTP POST ──>│     :api     │── publishes job ──> RabbitMQ (main queue)
             │  (Ktor/Netty)│                          │
             │  + Postgres  │                    ┌─────▼──────┐
             └─────────────┘                    │   :worker   │
                                                │  (consumer) │
                                                │  + Postgres │
                                                └─────────────┘

Retry topology (RabbitMQ):
  main queue ──(NACK/TTL)──> DLX ──> DLQ
```

**Modules**

| Module | Responsibility |
|---|---|
| `:shared` | Queue message models (`DeliveryJob`), environment config (`EnvConfig`), shared JSON instance (`AppJson`), RabbitMQ topology declaration |
| `:api` | Ktor/Netty server — webhook ingestion, source management, Flyway migrations, RabbitMQ topology init |
| `:worker` | RabbitMQ consumer — HTTP delivery, retry logic, delivery state updates *(planned)* |

**Persistence** — Postgres is the source of truth for all delivery state, idempotency keys, and audit history.
**Transport** — RabbitMQ is used exclusively as execution transport, not state storage.

---

## Implementation Status

### Completed

- [x] Multi-module Gradle project (`shared`, `api`, `worker`)
- [x] Docker Compose — PostgreSQL, RabbitMQ, PGAdmin
- [x] Database schema — `sources`, `destinations`, `destination_rules`, `events`, `deliveries` (Flyway `V1__init.sql`)
- [x] `:shared` — `EnvConfig`, `AppJson`, `DeliveryJob`, `RabbitMQTopology` (TTL + DLX + DLQ, idempotent declare)
- [x] `:api` — Ktor + Netty, JSON serialization (`ContentNegotiation`), `StatusPages`
- [x] `:api` — HikariCP connection pool + Exposed ORM + Flyway migrations on startup
- [x] `:api` — RabbitMQ connection + topology declaration on startup
- [x] `:api` — `GET /health` — DB connectivity check + HikariCP pool stats
- [x] `:api` — `POST /sources`, `GET /sources` — layered architecture (Repository → UseCase → Routes)
- [x] Unit tests — `SourceUseCase` (13 tests) and `SourceRoutes` (10 tests), zero I/O
- [x] Integration tests — repository layer and full HTTP stack via Testcontainers PostgreSQL (11 tests)
- [x] `:api` — Destination aggregate (`Destination` + `DestinationRule`) with unified repository, use case, and routes
- [x] `:api` — `POST /destinations`, `GET /destinations`, `GET /destinations/{id}`, `POST /destinations/{id}/rules`
- [x] `:api` — `NotFoundException` mapped to HTTP 404 via `StatusPages`
- [x] Unit and integration tests for destination — use case (33), routes (24), repository (14), API (11)

### Planned

- [ ] `POST /webhooks/{sourceName}` — ingest webhook event (HMAC validation, idempotency, publish to RabbitMQ)
- [ ] `:worker` — RabbitMQ consumer, HTTP delivery, retry logic, DLQ handling
- [ ] HMAC-SHA256 signature validation on ingestion
- [ ] Idempotency key deduplication

---

## Prerequisites

- Docker and Docker Compose
- JDK 21 (auto-downloaded via Gradle Foojay toolchain if not present)
- Gradle (via wrapper — no global install needed)

---

## How to run locally

**1. Start infrastructure**

```bash
docker-compose up -d
```

| Service | URL | Credentials |
|---|---|---|
| Postgres | `localhost:5432` | `webhookhub` / `webhookhub` |
| RabbitMQ AMQP | `localhost:5672` | `webhookhub` / `webhookhub` |
| RabbitMQ Management UI | http://localhost:15672 | `webhookhub` / `webhookhub` |
| PGAdmin | http://localhost:5050 | `admin@webhookhub.com` / `admin` |

**2. Set environment variables**

```bash
export DB_URL=jdbc:postgresql://localhost:5432/webhookhub
export DB_USER=webhookhub
export DB_PASSWORD=webhookhub

export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USER=webhookhub
export RABBITMQ_PASSWORD=webhookhub
export RABBITMQ_VHOST=webhookhub
```

**3. Run the API**

```bash
./gradlew :api:run
```

The API starts on `http://localhost:8080`.

**4. Run the Worker** *(planned)*

```bash
./gradlew :worker:run
```

---

## API Endpoints

### Health

```
GET /health
```

Returns database connectivity status and HikariCP connection pool statistics.

**Response `200 OK`**
```json
{
  "status": "UP",
  "db": "UP",
  "pool": {
    "total": 10,
    "active": 1,
    "idle": 9,
    "pending": 0
  }
}
```

Returns `503 Service Unavailable` with `"status": "DOWN"` if the database is unreachable.

---

### Sources

Sources represent external systems that send webhook events. Each source has a unique name and a generated HMAC secret used to verify incoming payloads.

#### Create a source

```
POST /sources
Content-Type: application/json
```

```json
{ "name": "github" }
```

**Response `201 Created`** — includes `hmacSecret` (shown only at creation time)
```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "name": "github",
  "hmacSecret": "a3f1c2...",
  "active": true,
  "createdAt": "2026-02-18T10:00:00Z"
}
```

Returns `400 Bad Request` if `name` is blank or exceeds 100 characters.

#### List sources

```
GET /sources
```

**Response `200 OK`** — `hmacSecret` is not exposed in this endpoint
```json
[
  {
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "name": "github",
    "active": true,
    "createdAt": "2026-02-18T10:00:00Z"
  }
]
```

---

### Destinations

Destinations are HTTP endpoints that receive webhook events. Each destination is created with one or more routing rules that determine which source/event-type pairs are delivered to it.

#### Create a destination

```
POST /destinations
Content-Type: application/json
```

```json
{
  "name": "my-service",
  "targetUrl": "https://my-service.example.com/webhook",
  "rules": [
    { "sourceName": "github", "eventType": "push" }
  ]
}
```

At least one rule is required. Returns `400 Bad Request` if `name` is blank or exceeds 100 characters, `targetUrl` is not a valid HTTP/HTTPS URL, or `rules` is empty.

**Response `201 Created`**
```json
{
  "id": "a1b2c3d4-...",
  "name": "my-service",
  "targetUrl": "https://my-service.example.com/webhook",
  "active": true,
  "createdAt": "2026-02-19T10:00:00Z",
  "rules": [
    { "id": "e5f6...", "sourceName": "github", "eventType": "push" }
  ]
}
```

#### List destinations

```
GET /destinations
```

**Response `200 OK`** — each destination includes its rules

#### Get a destination

```
GET /destinations/{id}
```

**Response `200 OK`** — destination with its rules.
Returns `404 Not Found` if the destination does not exist.

#### Add a rule to a destination

```
POST /destinations/{id}/rules
Content-Type: application/json
```

```json
{ "sourceName": "stripe", "eventType": "charge.created" }
```

**Response `201 Created`**
```json
{ "id": "f7a8...", "sourceName": "stripe", "eventType": "charge.created" }
```

Returns `404 Not Found` if the destination does not exist. Returns `400 Bad Request` if `sourceName` or `eventType` is blank.

---

## How to test

**Unit tests** (no Docker required)

```bash
./gradlew :api:test --tests "*UseCaseTest" --tests "*RoutesTest"
```

**Integration tests** (requires Docker for Testcontainers)

```bash
./gradlew :api:test
```

Testcontainers automatically pulls and manages isolated PostgreSQL containers. No running infrastructure needed.

**Full test suite**

```bash
./gradlew test
```

**Full clean build**

```bash
./gradlew clean build
```

---

## RabbitMQ Topology

Declared idempotently on every startup by `RabbitMQTopology.declare()` in `:shared`.

| Resource | Type | Configuration |
|---|---|---|
| `webhookhub` | direct exchange | main producer target |
| `webhookhub.dlx` | fanout exchange | receives expired / rejected messages |
| `webhookhub.deliveries` | durable queue | `x-message-ttl=30min`, `x-dead-letter-exchange=webhookhub.dlx` |
| `webhookhub.dlq` | durable queue | bound to DLX — requires manual replay |

---

## Webhook signing (HMAC-SHA256) *(planned)*

Every webhook source has a secret generated at creation time. The sender must include a signature header computed as:

```
X-Webhook-Signature: sha256=<HMAC-SHA256(secret, raw-request-body)>
```

The API validates the signature using constant-time comparison to prevent timing attacks.
Requests with a missing or invalid signature are rejected with `401 Unauthorized`.

---

## Operational notes

**Idempotency**

Duplicate deliveries are detected via a unique DB constraint on `(source_name, idempotency_key)`.
Re-sending the same event returns a successful no-op — no new records are created and no jobs are re-published.

**Retry policy** *(planned — worker)*

| Condition | Action |
|---|---|
| HTTP 5xx, 429, timeout, network error | Retryable — message re-queued via TTL + DLX |
| HTTP 4xx (except 429) | Non-retryable — message sent directly to DLQ |
| Max attempts exceeded | Message sent to DLQ |

**Dead Letter Queue (DLQ)**

Messages in the DLQ require manual inspection and replay. Monitor via the RabbitMQ Management UI at http://localhost:15672.

**Concurrency** *(planned — worker)*

The worker enforces a prefetch count and a concurrency cap to prevent overloading downstream destinations.

---

## Troubleshooting

**Postgres connection refused**
Verify the container is healthy: `docker-compose ps`. Wait for the health check to pass before starting the API.

**RabbitMQ exchanges/queues not found**
The API declares all topology on startup. Check the Management UI at http://localhost:15672.

**Flyway migration errors**
Check that `DB_URL`, `DB_USER`, and `DB_PASSWORD` point to the correct database. Migrations run automatically on API startup.

**Stop all infrastructure**

```bash
docker-compose down
```

To also remove persistent volumes:

```bash
docker-compose down -v
```
