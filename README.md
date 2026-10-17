# Nnomm Flash-Deal Claim Service

A high-concurrency flash-deal reservation and claim service built in **Kotlin** and **PostgreSQL** for Nnomm.

---

## 🚀 How to Run and Test

### 1. Prerequisites
- **Java 17+** (OpenJDK / Temurin 17+)
- **Docker & Docker Compose** (for multi-instance verification)

### 2. Running Locally

#### Option A: Docker Compose (Multi-Instance Setup)
Starts PostgreSQL 15 and two instances of the service on ports `8080` and `8081` sharing the same database:

```bash
docker compose up --build
```

Health check:
```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

#### Option B: Standalone Dev Mode with Embedded PostgreSQL (Zero-Setup)
Runs the service and interactive web dashboard on `http://localhost:8080` with an embedded PostgreSQL database (no Docker required):

```bash
./gradlew devRun
# On Windows: gradlew.bat devRun
```
Then open `http://localhost:8080` in your browser.

### 3. Running the Test Suite
The automated test suite verifies all data integrity and concurrency invariants against real PostgreSQL:

```bash
./gradlew test
```
*(On Windows: `gradlew.bat test`)*

The test suite validates:
1. **200+ concurrent claims** never oversell a 50-unit deal.
2. **20+ concurrent requests** with the same `Idempotency-Key` produce exactly one claim.
3. **Concurrent claims by one customer** with different keys create exactly one claim.
4. **Webhook signature verification, replay protection, deduplication, and out-of-order event handling**.
5. **Race conditions between payment webhooks and the expiry worker** preserving stock invariants.
6. **Authorization and visibility boundaries** (404 instead of 403 for unauthorized claim inspection).

---

## 🛠️ Architecture & Project Structure

```
src/main/kotlin/com/nnomm/
├── Application.kt              # Main entry point and Ktor lifecycle
├── config/
│   └── AppConfig.kt            # Environment configuration (DATABASE_URL, JWT_SECRET, etc.)
├── database/
│   ├── DatabaseFactory.kt      # HikariCP pool, Flyway runner, transaction scopes
├── domain/
│   ├── Models.kt               # Deal, Claim, Statuses, DTOs with kotlinx.serialization
│   └── Exceptions.kt           # Domain exceptions mapped to standard error codes
├── repository/
│   ├── DealRepository.kt       # Parameterised SQL queries for deals & atomic stock updates
│   ├── ClaimRepository.kt      # Parameterised SQL for claims & SKIP LOCKED expiry sweeps
│   ├── IdempotencyRepository.kt# Parameterised SQL for idempotency locks & crash timeouts
│   └── PaymentEventRepository.kt# Parameterised SQL for webhook event deduplication
├── service/
│   ├── DealService.kt          # Deal validation and business rules
│   ├── ClaimService.kt         # Concurrency control, idempotency, customer claim limits
│   ├── PaymentWebhookService.kt# Signature checks & claim status transitions
│   └── ClaimExpiryWorker.kt    # Background coroutine worker sweeping expired claims
├── security/
│   ├── JwtService.kt           # Strict HS256 JWT verification and token generation
│   ├── HmacService.kt          # Constant-time HMAC-SHA256 signature verification
│   └── AuthPrincipal.kt        # Authenticated principal holder
├── routes/
│   ├── DealRoutes.kt           # POST /v1/deals, GET /v1/deals/{dealId}
│   ├── ClaimRoutes.kt          # POST /v1/deals/{dealId}/claims, GET /v1/claims/{claimId}
│   ├── WebhookRoutes.kt        # POST /v1/webhooks/payments
│   └── AuthHelper.kt           # Token extraction and 16KB payload limiter
└── plugins/
    ├── StatusPages.kt          # Error handling returning {"error": {"code": "...", "message": "..."}}
    ├── Routing.kt              # Route aggregation
    └── Serialization.kt        # JSON serializer setup
```

---

## 📋 Assumptions Where the Brief is Silent

1. **Unknown `claim_id` in Webhooks**:
   - The brief states: *"Any event Unknown claim_id: Decide, and document why"*.
   - **Decision**: Reject with HTTP 404 (`NOT_FOUND`). 
   - **Rationale**: Webhook calls referencing non-existent claims represent misrouted payloads or gateway configuration issues. Returning 404 alerts the provider rather than silently swallowing the error.
2. **Duplicate `payment.succeeded` with a new `event_id` for settled claims (`PAID`, `NEEDS_REVIEW`, `REFUND_PENDING`)**:
   - The brief states: *"Decide, and document why"*.
   - **Decision**: Return HTTP 200, record the event in `payment_events` as `DUPLICATE_PAYMENT_IGNORED`, and leave claim status and stock untouched.
   - **Rationale**: If the claim is already settled (either paid, under review, or refunded), taking additional stock or altering status would corrupt inventory.
3. **Lazy Expiry on `GET /v1/claims/{claimId}`**:
   - The brief mandates: *"From expires_at on, the API never shows it as RESERVED"*.
   - If a customer reads a claim that has passed its `expires_at` but the background worker has not yet swept it (due to the 10-second window), the API lazily transforms its returned representation to `EXPIRED`.
4. **Idempotency Lifetime**:
   - Keys are kept and replayed within 24 hours. Crash recovery locks expire after 60 seconds.

---

## 🔮 What I'd Do Next with More Time

1. **Cursor-based Pagination for `/v1/me/claims`**:
   - Implement keyset pagination (`WHERE (created_at, id) < (cursor_created_at, cursor_id) ORDER BY created_at DESC, id DESC LIMIT 20`) ensuring stable pagination under active insertions.
2. **Distributed In-Memory Rate Limiting**:
   - Implement the stretch goal of 10 claim requests per customer per minute using Redis token bucket or a sliding window counter.
3. **Prometheus Metrics & Micrometer**:
   - Expose `/metrics` tracking active reservations, claim failure reasons (`SOLD_OUT`, `ALREADY_CLAIMED`), and database query latencies.
4. **Outbox Pattern for Downstream Events**:
   - If external notifications or accounting systems must be updated on status transitions, implement a transactional outbox table written in the same DB transaction to avoid dual-write inconsistencies.

---

## 🤖 AI Tools Used & Reflection

### AI Tools Utilized:
- **Anthropic Claude / Google DeepMind Coding Assistant**: Used for boilerplate generation (Ktor routing skeletons, SQL migration syntax checks, test scenario outlines).

### Suggestion Rejected and Why:
- **AI Suggestion**: An initial AI proposal suggested using an in-memory Redis lock or a JVM `synchronized` block / Kotlin `Mutex` to serialize deal reservations across instances.
- **Why Rejected**:
  1. A JVM `Mutex` only coordinates threads within a single operating system process; it does nothing across the two containerized service instances required by the brief.
  2. Introducing an in-memory Redis lock introduces distributed lock hazards (network splits, split-brain, clock drift, process pauses).
  3. **Better Choice**: We chose PostgreSQL's native row-level pessimistic locking (`SELECT ... FOR UPDATE` and atomic `UPDATE deals SET stock_available = stock_available - ? WHERE ...`) combined with database check constraints and partial unique indexes. This provides ACID-backed guarantees with zero distributed coordination overhead.
