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
  main queue ──(NACK/TTL)──> retry queue ──(DLX after TTL)──> main queue
                                                  └─(max attempts)──> DLQ
```

**Modules**

| Module | Responsibility |
|---|---|
| `:shared` | Queue message models, environment config, RabbitMQ topology declaration |
| `:api` | Ktor server — webhook ingestion, HMAC validation, idempotency, admin endpoints, Flyway migrations |
| `:worker` | RabbitMQ consumer — HTTP delivery, retry logic, delivery state updates |

**Persistence** — Postgres is the source of truth for all delivery state, idempotency keys, and audit history.
**Transport** — RabbitMQ is used exclusively as execution transport, not state storage.

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
| PGAdmin | http://localhost:5050 | `admin@webhookhub.local` / `admin` |

**2. Set environment variables**

```bash
export DB_URL=jdbc:postgresql://localhost:5432/webhookhub
export DB_USER=webhookhub
export DB_PASS=webhookhub
export RABBIT_HOST=localhost
export RABBIT_PORT=5672
export RABBIT_USER=webhookhub
export RABBIT_PASS=webhookhub
export RABBIT_VHOST=webhookhub
```

**3. Run the API**

```bash
./gradlew :api:run
```

**4. Run the Worker**

```bash
./gradlew :worker:run
```

---

## How to test

**Unit tests** (no Docker required)

```bash
./gradlew test
```

**Integration tests** (requires Docker for Testcontainers)

```bash
./gradlew check
```

Integration tests use Testcontainers to spin up isolated Postgres and RabbitMQ containers automatically.

**Full clean build**

```bash
./gradlew clean build
```

---

## Webhook signing (HMAC-SHA256)

Every webhook source has a secret. The sender must include a signature header computed as:

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

**Retry policy**

| Condition | Action |
|---|---|
| HTTP 5xx, 429, timeout, network error | Retryable — message re-queued via TTL + DLX |
| HTTP 4xx (except 429) | Non-retryable — message sent directly to DLQ |
| Max attempts exceeded | Message sent to DLQ |

Retries use per-message TTL on a dedicated retry queue. After the TTL expires, RabbitMQ routes the message back to the main queue via a Dead Letter Exchange (DLX).

**Dead Letter Queue (DLQ)**

Messages in the DLQ require manual inspection and replay. Monitor the DLQ via the RabbitMQ Management UI at http://localhost:15672.

**Concurrency**

The worker enforces a prefetch count and a Semaphore-based concurrency cap to prevent overloading downstream destinations.

---

## Troubleshooting

**Postgres connection refused**
Verify the container is healthy: `docker-compose ps`. Wait for the health check to pass before starting the API.

**RabbitMQ exchanges/queues not found**
The `:shared` module declares all topology on startup. Ensure the worker or API has connected at least once, or check the Management UI at http://localhost:15672.

**Flyway migration errors**
Check that `DB_URL`, `DB_USER`, and `DB_PASS` point to the correct database. Migrations run automatically on API startup.

**Stop all infrastructure**

```bash
docker-compose down
```

To also remove persistent volumes:

```bash
docker-compose down -v
```
