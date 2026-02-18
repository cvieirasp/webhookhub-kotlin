# Repository Development Rules (WebhookHub)

## 1) Purpose of this file
This document defines **repository-wide development rules**: architecture guidelines, coding standards, PR checklist, commit conventions, testing requirements, and acceptance criteria.  
It is the single source of truth for how we build and evolve **WebhookHub**.

---

## 2) Repository structure and ownership

### Modules
- **`:shared`**
  - Shared contracts and utilities used by both API and Worker:
    - queue message models
    - environment configuration model
    - JSON configuration
    - RabbitMQ topology declaration (exchanges/queues/bindings)
- **`:api`**
  - Ktor server for webhook ingestion and admin endpoints
  - persistence (Exposed + HikariCP)
  - Flyway migrations
  - publishes delivery jobs to RabbitMQ
- **`:worker`**
  - consumes delivery jobs and performs HTTP delivery
  - updates delivery state in Postgres
  - implements retry via RabbitMQ TTL + DLX + DLQ

### Ownership rules
Any change touching **queue topology**, **delivery status transitions**, **idempotency**, or **retry/DLQ policy** must include:
- updated tests (unit/integration as applicable)
- updated README (if it changes how to run/test/operate)
- a short design note in the PR description

---

## 3) Architecture guidelines (non-negotiable)

### Layering
All modules must keep responsibilities separated:

- **domain**
  - core models, invariants, rules, error types (sealed classes)
  - no framework dependencies (no Ktor, no Exposed)
- **application**
  - use cases / orchestration logic (ingest, create destination, dispatch deliveries)
  - depends on domain + interfaces (ports)
- **infra**
  - adapters: database repositories (Exposed), RabbitMQ client/publisher/consumer, HTTP client
  - external concerns (timeouts, retries, pool configuration)
- **api** (in `:api` only)
  - routing, DTO mapping, request/response validation, status mapping

> You can keep packages lightweight initially, but do not mix responsibilities (e.g., no SQL inside routes).

### Source of truth rule
- **Postgres is the source of truth** for:
  - idempotency
  - delivery attempts and status
  - audit trail and traceability
- RabbitMQ is **execution transport**, not state storage.

### Idempotency (must be enforced in DB)
- Enforce via **unique constraint** for `(source_name, idempotency_key)` (or equivalent).
- The API must treat duplicates as a successful no-op and must not publish new jobs.

### Retry/DLQ policy (must be explicit and testable)
- Retry uses **TTL per message** in the retry queue.
- Retry queue must be configured with **DLX back to main**.
- DLQ is final destination when:
  - max attempts exceeded, or
  - error classified as non-retryable (e.g., most 4xx except 429)

### Consistency and acking
The worker must only **ACK** a RabbitMQ message after:
- the delivery attempt is recorded (attempt count, last_error, status) and committed
- and (if applicable) the retry/dlq publish action is performed

---

## 4) Technology stack (standardized)

### Kotlin
- Kotlin JVM, **toolchain 21**
- Coroutines used for worker concurrency control

### API (Ktor)
- Ktor server: Netty engine
- Content Negotiation + Kotlinx Serialization
- StatusPages for centralized error handling
- CallLogging for request logging

### Persistence
- Exposed (SQL DSL)
- HikariCP (connection pool)
- Postgres JDBC driver
- Flyway migrations

### Messaging
- RabbitMQ Java Client
- durable exchanges/queues
- persistent messages

### Worker delivery
- Ktor HTTP client (CIO engine)
- strict timeouts

### Code quality (recommended)
- detekt (static analysis) and/or ktlint (format)
- EditorConfig enforced

---

## 5) Development standards

### Configuration
- Configuration must come from **environment variables** only:
  - `DB_URL`, `DB_USER`, `DB_PASS`
  - `RABBIT_HOST`, `RABBIT_PORT`, `RABBIT_USER`, `RABBIT_PASS`
- No secrets committed. Use example values in docs only.

### Logging & correlation
- Every ingest request generates (or accepts) a `correlationId`.
- Log entries must include (when available):
  - `correlationId`
  - `sourceName`, `eventType`
  - `eventId`, `deliveryId`, `destinationId`
  - `attempt`, `status`

### Security rules
- Webhook authentication is **HMAC-SHA256** per source secret.
- Signature comparison must be **constant-time**.
- Never log secrets or full sensitive payloads. Prefer:
  - payload size
  - event id
  - payload hash/idempotency key

### Performance rules
- Worker must set RabbitMQ **prefetch** and enforce a concurrency cap (Semaphore).
- Use a single, reusable HTTP client instance in the worker.
- Use Hikari pool; do not open raw JDBC connections per request.

---

## 6) Git workflow & commit conventions

### Branch naming
- `feat/<short-topic>`
- `fix/<short-topic>`
- `chore/<short-topic>`
- `docs/<short-topic>`
- `test/<short-topic>`

### Commit message format (Conventional Commits)
Use one of:
- `feat: ...`
- `fix: ...`
- `chore: ...`
- `docs: ...`
- `test: ...`
- `refactor: ...`

Optional scope:
- `feat(api): ...`
- `fix(worker): ...`
- `refactor(shared): ...`

Rules:
- Subject line in imperative mood: “add”, “fix”, “refactor”
- Keep it concise and meaningful
- Reference issues if used: `refs #123`

---

## 7) Pull Request checklist (must be satisfied)

### PR description must include
- What changed (1–3 bullets)
- Why it changed
- How to test (commands and expected outcome)
- Any risks or migration notes (if DB/queue changes)

### Mandatory checks
- [ ] Builds locally: `./gradlew clean build`
- [ ] Formatting/static checks pass (if configured)
- [ ] No secrets or credentials committed
- [ ] README updated if run/test/setup changed
- [ ] Flyway migrations added/updated if schema changed (no manual DB edits)
- [ ] RabbitMQ topology changes are backward-compatible or include migration steps
- [ ] API changes include validation and consistent error mapping
- [ ] Worker changes preserve ACK safety and do not introduce infinite retry loops
- [ ] Unit tests added/updated for business rules
- [ ] Integration tests added/updated for DB/queue behavior (when applicable)

### High-risk change extra checklist
Any PR touching idempotency, retry policy, delivery status, or topology:
- [ ] Includes at least one integration test covering the change
- [ ] Includes a short note in PR about expected operational impact

---

## 8) Testing rules (unit + integration)

### Unit tests (fast, deterministic)
Must cover:
- backoff calculation
- retryability classification:
  - retryable: timeouts, network errors, 5xx, 429
  - non-retryable: most 4xx (except 429)
- domain state transitions:
  - pending → delivered
  - pending/retrying → dead
- idempotency key generation logic
- HMAC validation logic (functional correctness)

Rules:
- No Docker, no Testcontainers
- No time-based sleeps
- Use fake clocks/time abstraction if needed

### Integration tests (infrastructure-realistic)
Use Testcontainers where possible:
- Postgres container:
  - validates Flyway migrations
  - validates unique constraints and indexes
- RabbitMQ container:
  - validates exchanges/queues bindings
  - validates TTL+DLX behavior (message returns to main queue)
- HTTP destination simulation:
  - use a lightweight mock HTTP server for 2xx/4xx/5xx/timeout cases

Integration scenarios that must exist (at minimum):
- ingest with valid signature produces event + deliveries and publishes jobs
- duplicate ingest does not recreate records and does not republish
- worker delivers successfully and marks delivery **DELIVERED**
- worker retries on retryable failures and eventually DLQs on max attempts

Rules:
- Integration tests must be isolated:
  - clean DB between tests (truncate or schema reset)
  - avoid flaky sleeps; poll with bounded timeouts
- Keep integration suite stable and not overly slow (focus on core flows)

---

## 9) Acceptance criteria (definition of done)

A feature/PR is considered “done” only if:
- functional behavior matches intended flow end-to-end
- errors are handled consistently and safely
- tests exist at the right level (unit/integration)
- documentation is updated (README if needed)
- no regressions in idempotency, retry, or delivery state behavior

### MVP acceptance criteria for the repository
- webhook ingestion supports HMAC validation per source
- idempotency is enforced by DB constraint and API behavior
- destinations and routing rules determine which deliveries are created
- worker delivers events to HTTP destinations asynchronously
- retry uses TTL + DLX and respects backoff and max attempts
- DLQ receives messages for non-retryable failures or max-attempt exceed
- observability: correlationId and key IDs present in logs
- a minimal integration test suite proves the flow with Postgres + RabbitMQ

---

## 10) README policy (must be in English)
The repository README **must be written in English** and must include:

### Required sections
- Project overview (WebhookHub, what it does)
- Architecture (API + Worker + Postgres + RabbitMQ retry topology)
- Prerequisites (Docker, JDK 21, Gradle)
- How to run locally
- How to test (unit + integration)
- Webhook signing (HMAC-SHA256)
- Operational notes (retry policy, max attempts, DLQ)
- Troubleshooting

### Documentation rules
- Do not include real secrets or production URLs.
- Prefer step-by-step commands.
- Keep examples minimal but complete.

---

## 11) Non-goals (for the initial version)
To keep scope professional and stable:
- no user-facing authentication/authorization (beyond webhook HMAC)
- no multi-tenant model (can be added later)
- no advanced observability stack (OTel/Prometheus) in MVP
- no UI
