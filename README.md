# Nnomm Flash-Deal Claim Service

A high-concurrency flash-deal reservation and claim service built with **Spring Boot 3**, **Kotlin**, **PostgreSQL**, and **Flyway** for Nnomm.

> 📘 **Architecture & Concurrency Design**: Complete walkthroughs of transaction isolation, SQL locking, idempotency guarantees, and race condition resolutions can be found in **[DESIGN.md](DESIGN.md)**.

---

## 🚀 How to Run and Test

### 1. Technology Stack
- **Language**: Kotlin (JVM 17)
- **Framework**: Spring Boot 3.2.3 (Web MVC, JDBC)
- **Database**: PostgreSQL 15
- **Migrations**: Flyway
- **Build Tool**: Gradle (Kotlin DSL)
- **Testing**: JUnit 5 + Testcontainers + Embedded PostgreSQL
- **Multi-Instance Deployment**: Docker Compose

---

### 2. Running Locally

#### Option A: Standalone Dev Mode with Embedded PostgreSQL (Zero-Setup)
Runs the Spring Boot service and interactive web dashboard on `http://localhost:8080` with an embedded PostgreSQL database (no external Docker required):

```bash
./gradlew devRun
# On Windows:
gradlew.bat devRun
```
Then open your browser to **`http://localhost:8080`**.

#### Option B: Docker Compose (Multi-Instance Setup)
Starts PostgreSQL 15 and two Spring Boot instances of the service on ports `8080` and `8081` sharing the same database:

```bash
docker compose up --build
```

Health check:
```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

---

### 3. Running the Test Suite
The automated test suite verifies all data integrity and concurrency invariants against real PostgreSQL (using Testcontainers when Docker is present, with embedded PostgreSQL fallback):

```bash
./gradlew test
# On Windows:
gradlew.bat test
```

The test suite validates:
1. **200+ concurrent claims** never oversell a 50-unit deal.
2. **20+ concurrent requests** with the same `Idempotency-Key` produce exactly one claim.
3. **Concurrent claims by one customer** with different keys create exactly one claim.
4. **Webhook signature verification, replay protection, deduplication, and out-of-order event handling**.
5. **Race conditions between payment webhooks and the expiry worker** preserving stock invariants.
6. **Authorization and visibility boundaries** (404 instead of 403 for unauthorized claim inspection).
7. **Spring MVC MockMvc controller integration and 16KB payload limits**.

---

## 🛠️ Architecture & Project Structure

```
src/main/kotlin/com/nnomm/
├── Application.kt              # Spring Boot @SpringBootApplication entry point
├── config/
│   └── AppConfig.kt            # Spring Boot environment properties (DATABASE_URL, JWT_SECRET, etc.)
├── database/
│   └── DatabaseFactory.kt      # Spring-managed DataSource connection & transaction manager
├── domain/
│   ├── Models.kt               # Deal, Claim, Statuses, DTOs with Jackson serialization
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
│   └── ClaimExpiryWorker.kt    # Scheduled background worker sweeping expired claims
├── security/
│   ├── JwtService.kt           # Strict HS256 JWT verification and token generation
│   ├── HmacService.kt          # Constant-time HMAC-SHA256 signature verification
│   ├── AuthPrincipal.kt        # Authenticated principal holder
│   ├── AuthHelper.kt           # Request authentication extractor
│   └── PayloadSizeAndCachingFilter.kt # Enforces 16KB limit & caches request body for HMAC
└── controller/
    ├── DealController.kt       # POST /v1/deals, GET /v1/deals/{dealId}
    ├── ClaimController.kt      # POST /v1/deals/{dealId}/claims, GET /v1/claims/{claimId}
    ├── WebhookController.kt    # POST /v1/webhooks/payments
    ├── DashboardController.kt  # GET /, GET /health, GET /dev/*
    └── GlobalExceptionHandler.kt # Maps AppException to structured JSON error responses
```

---

## 📋 Assumptions Made

1. **Payment Amount Validation**: If a `payment.succeeded` event arrives with an amount differing from `claim.amount_paise`, the claim transitions to `NEEDS_REVIEW`. Units stay held until human reconciliation.
2. **Crash Recovery Timeout**: The locked-until window for in-progress claims is set to 60 seconds (`now.plusSeconds(60)`). If a node dies mid-request, another request can safely recover and retry after 60 seconds without hanging permanently.
3. **Admin Token Generation**: In development mode, `GET /dev/tokens` generates valid HS256 JWT tokens for interactive browser testing.
4. **Deal Expiry Boundary**: Per the specification, a deal is active from `starts_at` up to, but not including, `ends_at` (`now >= starts_at && now < ends_at`).
5. **Immediate Expiry Visibility**: Once `Instant.now() >= claim.expires_at`, `GET /v1/claims/{claimId}` returns status `EXPIRED` even if the background reaper has not yet executed its sweep.

---

## 🔮 What I'd Do Next with More Time

1. **Redis-Assisted Pre-Claim Token Bucket**: Place an in-memory Redis cluster with atomic Lua scripts ahead of Postgres to absorb 50,000+ RPS flash-deal spikes before querying PostgreSQL.
2. **Outbox Pattern for External Notifications**: Use a transactional outbox table in Postgres to emit Kafka / RabbitMQ events when claims transition to `PAID` or `REFUND_PENDING`.
3. **Prometheus & OpenTelemetry Metrics**: Instrument HikariCP connection pool metrics, lock acquisition times, and HTTP latency histograms.
4. **Automated Refund Integration**: Wire an outbound HTTPS client with retry queues to call the payment provider's refund endpoint when transitions to `REFUND_PENDING` occur.

---

## 🤖 AI Tools Used & Reflection

- **Google DeepMind Antigravity Coding Assistant**: Used extensively for AI-assisted development, including Spring Boot configuration, Kotlin implementation, SQL migrations, and automated test generation.
- **Critical Evaluation of AI Suggestions**: Evaluated AI-generated approaches for handling concurrent deal claims. The solution uses PostgreSQL pessimistic row-level locking (SELECT ... FOR UPDATE), database constraints, and transactional operations to prevent overselling and maintain data integrity under concurrent requests.
- **Concurrency & Reliability**: Used SELECT ... FOR UPDATE SKIP LOCKED for background cleanup processing, helping prevent workers from processing the same eligible records simultaneously.
- **Learning & Validation**: Used AI assistance to explore implementation choices and understand trade-offs between optimistic and pessimistic locking, with testing focused on concurrency, transaction safety, and data integrity.
