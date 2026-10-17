# Architecture & Interview Preparation Guide: Nnomm Flash-Deal Claim Service

This guide is designed to give you complete mastery over the codebase:
1. **Where is What**: A complete structural map of every file and component.
2. **How to Make Changes Easily**: Step-by-step instructions for live coding tasks.
3. **Interview Preparation**: Anticipated questions, edge cases, and explanations for the 60-minute live session.

---

## Part 1: Where is What (Codebase Architecture)

### 1. High-Level Flow Diagram
```
                     +---------------------------------------+
                     |           Client / Mobile App          |
                     +---------------------------------------+
                                  |                 |
                       Bearer JWT |                 | Webhook (HMAC Signature)
                                  v                 v
           +-----------------------------+   +-----------------------------+
           | DealRoutes & ClaimRoutes    |   | WebhookRoutes               |
           | (/v1/deals, /v1/claims)     |   | (/v1/webhooks/payments)     |
           +-----------------------------+   +-----------------------------+
                          |                                 |
                          v                                 v
           +-----------------------------+   +-----------------------------+
           | DealService & ClaimService  |   | PaymentWebhookService       |
           | - Validation (16KB, limits) |   | - Verify HMAC (raw bytes)   |
           | - Idempotency Lock (60s)    |   | - Deduplication (event_id)  |
           | - Customer Limits (1 active)|   | - State Transition Machine  |
           +-----------------------------+   +-----------------------------+
                          \                                 /
                           \                               /
                            v                             v
           +---------------------------------------------------------------+
           |                    Database Repositories                      |
           | (DealRepository, ClaimRepository, IdempotencyRepository)      |
           +---------------------------------------------------------------+
                                          |
                                          | Parameterised SQL / Pessimistic Locks
                                          v
           +---------------------------------------------------------------+
           |                    PostgreSQL Database                        |
           | - deals (stock_available >= 0)                                |
           | - claims (Partial Unique Index on active claims)              |
           | - idempotency_keys (customer_id, key PK)                     |
           | - payment_events (event_id PK)                                |
           +---------------------------------------------------------------+
                                          ^
                                          | Sweeps every 1s (SKIP LOCKED)
           +---------------------------------------------------------------+
           |         ClaimExpiryWorker (Background Coroutine)              |
           +---------------------------------------------------------------+
```

---

### 2. File and Package Directory Map

| Path | Purpose & Key Components |
| :--- | :--- |
| `src/main/kotlin/com/nnomm/Application.kt` | **Server Entrypoint**. Runs Flyway migrations, configures Ktor Netty, launches the `ClaimExpiryWorker`, and shuts down gracefully. |
| `src/main/kotlin/com/nnomm/config/AppConfig.kt` | **Configuration**. Reads `DATABASE_URL`, `JWT_SECRET`, `WEBHOOK_SECRET`, `CLAIM_TTL_SECONDS` from environment variables. |
| `src/main/kotlin/com/nnomm/database/DatabaseFactory.kt` | **Database & Connection Pooling**. HikariCP configuration, Flyway migration trigger, `withConnection`, and `withTransaction` blocks. |
| `src/main/kotlin/com/nnomm/domain/Models.kt` | **Data Entities & Serializers**. `Deal`, `Claim`, `ClaimStatus`, `CreateDealRequest`, `ClaimRequest`, `WebhookPayload`, and ISO-8601 UTC serializers. |
| `src/main/kotlin/com/nnomm/domain/Exceptions.kt` | **Domain Exceptions**. Maps to required HTTP error codes: `SOLD_OUT`, `ALREADY_CLAIMED`, `DEAL_NOT_LIVE`, `IDEMPOTENCY_KEY_INVALID`, `PAYLOAD_TOO_LARGE`, etc. |
| `src/main/kotlin/com/nnomm/security/JwtService.kt` | **JWT Security**. Verifies HS256 tokens strictly (rejects `none` or other algorithms), validates `sub`, `role`, and `restaurant_id`. |
| `src/main/kotlin/com/nnomm/security/HmacService.kt` | **Webhook Cryptography**. Verifies HMAC-SHA256 signatures over raw body bytes with constant-time comparison and $|t - now| \le 300s$ protection. |
| `src/main/kotlin/com/nnomm/security/AuthPrincipal.kt` | Security holder object passed to service methods. |
| `src/main/kotlin/com/nnomm/repository/DealRepository.kt` | **Deal Data Access**. Atomic stock decrement (`UPDATE ... WHERE stock_available >= ?`), stock increment, and deal queries. |
| `src/main/kotlin/com/nnomm/repository/ClaimRepository.kt` | **Claim Data Access**. Claim creation, status updates, active claim check, and batch expiry with `FOR UPDATE SKIP LOCKED`. |
| `src/main/kotlin/com/nnomm/repository/IdempotencyRepository.kt` | **Idempotency Data Access**. Row locking (`FOR UPDATE`), 60s crash lease lock, result storage, and deletion. |
| `src/main/kotlin/com/nnomm/repository/PaymentEventRepository.kt` | **Webhook Deduplication**. Event record table preventing double-processing of identical `event_id`s. |
| `src/main/kotlin/com/nnomm/service/DealService.kt` | **Deal Business Logic**. Admin authorization, deal validation (1-100 chars, starts < ends, stock limits). |
| `src/main/kotlin/com/nnomm/service/ClaimService.kt` | **Core Claim Engine**. Concurrency control, idempotency resolution, deal live-window check, stock reservation, and visibility checks. |
| `src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt` | **Webhook Processing**. State transitions: `PAID`, `NEEDS_REVIEW` (amount mismatch), `REFUND_PENDING` (late payment), and duplicate handling. |
| `src/main/kotlin/com/nnomm/service/ClaimExpiryWorker.kt` | **Background Reaper**. Runs every 1 second, finds overdue claims via `SKIP LOCKED`, marks them `EXPIRED`, and returns stock to deals. |
| `src/main/kotlin/com/nnomm/routes/AuthHelper.kt` | Helper to extract JWT principal and enforce the 16KB body size limit (`MAX_BODY_SIZE_BYTES`). |
| `src/main/kotlin/com/nnomm/routes/DealRoutes.kt` | HTTP endpoints for `POST /v1/deals` and `GET /v1/deals/{dealId}`. |
| `src/main/kotlin/com/nnomm/routes/ClaimRoutes.kt` | HTTP endpoints for `POST /v1/deals/{dealId}/claims` and `GET /v1/claims/{claimId}`. |
| `src/main/kotlin/com/nnomm/routes/WebhookRoutes.kt` | HTTP endpoint for `POST /v1/webhooks/payments`. |
| `src/main/kotlin/com/nnomm/plugins/StatusPages.kt` | Formats all exceptions into the mandated shape: `{"error": {"code": "...", "message": "..."}}`. |
| `src/main/resources/db/migration/V1__init_schema.sql` | SQL schema, check constraints, partial unique index, and performance indexes. |

---

## Part 2: How to Make Changes Easily (Live Coding Playbook)

During your live session, the interviewers will ask you to change or add features live. Here is how to do each common modification cleanly:

### Scenario 1: Add a New Field to `Deal` (e.g., `description: String`)
1. **Migration**:
   - Create `src/main/resources/db/migration/V2__add_description_to_deals.sql`:
     ```sql
     ALTER TABLE deals ADD COLUMN description TEXT DEFAULT '';
     ```
2. **Domain Models** ([Models.kt](src/main/kotlin/com/nnomm/domain/Models.kt)):
   - Add `val description: String = ""` to `Deal` and `CreateDealRequest`.
3. **Repository** ([DealRepository.kt](src/main/kotlin/com/nnomm/repository/DealRepository.kt)):
   - Update `create()` SQL insert statement to include `description`.
   - Update `mapRow()`: `description = rs.getString("description")`.
4. **Validation** ([DealService.kt](src/main/kotlin/com/nnomm/service/DealService.kt)):
   - Add validation check if required (e.g., `if (req.description.length > 500) ...`).

### Scenario 2: Change Claim Quantity Limits (e.g., Allow 1 to 5 Units)
1. **Database Constraint** ([V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql)):
   - In a new migration: `ALTER TABLE claims DROP CONSTRAINT claims_quantity_check; ALTER TABLE claims ADD CONSTRAINT claims_quantity_check CHECK (quantity >= 1 AND quantity <= 5);`
2. **Validation in Service** ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt)):
   - Change `if (request.quantity !in 1..3)` to `1..5`.

### Scenario 3: Add Customer Cancellation Feature (`POST /v1/claims/{claimId}/cancel`)
1. **Domain Model**:
   - Add `CANCELLED` to `enum class ClaimStatus` in [Models.kt](src/main/kotlin/com/nnomm/domain/Models.kt).
   - In SQL: update `CHECK (status IN (...))` to include `'CANCELLED'`.
2. **Service Method** in [ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt):
   ```kotlin
   fun cancelClaim(principal: AuthPrincipal, claimId: UUID) {
       db.withTransaction { conn ->
           val claim = claimRepository.findByIdForUpdate(conn, claimId)
               ?: throw NotFoundException("Claim not found")
           if (principal.role != UserRole.customer || principal.sub != claim.customer_id) {
               throw NotFoundException("Claim not found")
           }
           if (claim.status != ClaimStatus.RESERVED) {
               throw ValidationException("Only RESERVED claims can be cancelled")
           }
           claimRepository.updateStatus(conn, claim.id, ClaimStatus.CANCELLED)
           dealRepository.incrementStock(conn, claim.deal_id, claim.quantity)
       }
   }
   ```
3. **Route** in [ClaimRoutes.kt](src/main/kotlin/com/nnomm/routes/ClaimRoutes.kt):
   - Add `post("/claims/{claimId}/cancel") { ... }`.

### Scenario 4: Change Expiry TTL to be Per-Deal Instead of Global
1. **Migration**:
   - `ALTER TABLE deals ADD COLUMN claim_ttl_seconds INT NOT NULL DEFAULT 600;`
2. **Claim Creation**:
   - In `ClaimService.executeClaimTransaction`, instead of `config.claimTtlSeconds`, use `deal.claim_ttl_seconds`:
     ```kotlin
     val expiresAt = now.plusSeconds(deal.claim_ttl_seconds.toLong())
     ```

### Scenario 5: Add a Status Filter to Deal Claims Query (Stretch Goal)
- `GET /v1/deals/{dealId}/claims?status=PAID`
- In [ClaimRepository.kt](src/main/kotlin/com/nnomm/repository/ClaimRepository.kt):
  ```sql
  SELECT * FROM claims WHERE deal_id = ? AND (? IS NULL OR status = ?) ORDER BY created_at DESC;
  ```
- Verify admin owns the deal (`deal.restaurant_id == principal.restaurantId`), else return 404!

---

## Part 3: What Can Be Asked in the Interview

### The 7 Core Architectural Questions (From Brief)

#### Q1: "Two customers on different instances claim the last unit at the same instant. Walk through the SQL each request runs and what PostgreSQL does with it. Why wouldn't a Kotlin Mutex or synchronized block fix this?"
- **Answer Key Points**:
  1. Both call `ClaimService.claimDeal()`, opening DB transactions.
  2. Both execute `SELECT ... FROM deals WHERE id = ? FOR UPDATE`.
  3. Instance 1 acquires the row lock. Instance 2 blocks in PostgreSQL.
  4. Instance 1 sees `stock_available = 1`, decrements to 0, inserts claim, commits.
  5. Instance 2 unblocks under `READ COMMITTED`. PostgreSQL re-reads the committed row, sees `stock_available = 0`, fails the check, throws `SoldOutException`, and rolls back.
  6. **Why Mutex fails**: A Kotlin `Mutex` is an in-memory construct local to one JVM. Instance 1 and Instance 2 run in separate operating system processes and separate containers; they share no heap or memory. Only PostgreSQL can coordinate them.

#### Q2: "Your service commits a claim, then crashes before responding. The client retries with the same Idempotency-Key. What happens? What if it crashed just before the commit?"
- **Answer Key Points**:
  1. **Crashed after commit**: The claim exists in `claims` and the 201 response is saved in `idempotency_keys`. On retry, the service queries `idempotency_keys`, sees `status_code = 201`, and returns the stored payload with `Idempotent-Replayed: true`. No duplicate claim is created.
  2. **Crashed before commit**: The DB transaction rolled back automatically when the connection dropped. The claim was never created and stock was never decremented. The initial record in `idempotency_keys` has `status_code = NULL` with `locked_until = created_at + 60s`. A retry within 60s gets 409 `REQUEST_IN_PROGRESS`. After 60s, the retry renews the lock and executes cleanly.

#### Q3: "A payment.succeeded webhook and your expiry job reach the same claim in the same millisecond. List every possible ordering, with the final claim status and stock for each."
- **Answer Key Points**:
  - Both lock the claim row (`FOR UPDATE`), so they are strictly serialized:
  - **Ordering A (Expiry wins lock first)**:
    - Expiry marks claim `EXPIRED`, increments `stock_available` by quantity.
    - Webhook runs second, sees status `EXPIRED`. Per spec for late payment, status becomes `REFUND_PENDING`. Units stay in stock.
    - *Final Status*: `REFUND_PENDING`. *Stock*: Units returned.
  - **Ordering B (Webhook wins lock first, before `expires_at`)**:
    - Webhook marks claim `PAID`. Units remain sold.
    - Expiry runs second, sees status is not `RESERVED`, does nothing.
    - *Final Status*: `PAID`. *Stock*: Units kept sold.
  - **Ordering C (Webhook wins lock first, after `expires_at`)**:
    - Webhook sees late arrival, increments `stock_available`, marks `REFUND_PENDING`.
    - Expiry runs second, ignores claim because status is `REFUND_PENDING`.
    - *Final Status*: `REFUND_PENDING`. *Stock*: Units returned.
  - *Result*: In all cases, `stock_available + active_units == stock_total`.

#### Q4: "Why must the webhook signature cover the raw body rather than the parsed JSON? What does the timestamp protect against, and why compare signatures in constant time?"
- **Answer Key Points**:
  1. **Raw Body**: HMAC-SHA256 operates on raw bytes. JSON parsers don't preserve exact key ordering, formatting, or whitespace. Serializing parsed JSON produces a different byte array, causing false signature verification failures.
  2. **Timestamp ($t$)**: Protects against replay attacks. Even if an attacker intercepts a valid signed payload, they cannot replay it after 300 seconds.
  3. **Constant Time**: Standard string equality fails fast on the first non-matching byte, creating a timing side-channel. Attackers can measure response times to guess the signature byte-by-byte. `MessageDigest.isEqual` checks all bytes in constant time.

#### Q5: "Which transaction isolation level do you use, and why is it enough? Name an anomaly you'd see with a weaker approach."
- **Answer Key Points**:
  - We use `READ COMMITTED` (PostgreSQL default).
  - It is sufficient because we use pessimistic locking (`FOR UPDATE`) and atomic conditional updates (`UPDATE ... WHERE stock_available >= ?`). These serialize concurrent access on the exact rows being modified, avoiding serialization failure retries needed in `SERIALIZABLE`.
  - **Anomaly with weaker approach**: Lost updates. If two concurrent transactions read `stock_available = 1` and update `stock_available = stock_available - 1` without locking, both write 0, selling 2 units when only 1 was available.

#### Q6: "Which constraint or index does the most work in your design? What breaks if someone drops it?"
- **Answer Key Points**:
  - The partial unique index:
    ```sql
    CREATE UNIQUE INDEX uq_active_customer_claim
    ON claims (customer_id, deal_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
    ```
  - **What it does**: Enforces "at most one active claim per customer per deal" across all instances and concurrent threads at the database storage layer. When a claim expires or is refunded, it automatically leaves the partial index without needing deletion.
  - **What breaks if dropped**: A single customer sending concurrent requests with different idempotency keys would race past application-level checks and claim multiple units.

#### Q7: "A deal goes viral and claim traffic grows 100×. What fails first, and what would you change?"
- **Answer Key Points**:
  - **What fails first**: Database row lock contention on the single `deals` row. Hundreds of threads waiting on `FOR UPDATE` on one row saturate the PostgreSQL connection pool and HikariCP.
  - **Changes**:
    1. Offload reservations to Redis using an atomic Lua script (`DECRBY` with check) or Redis Cell.
    2. Write successful reservations asynchronously to PostgreSQL via Kafka/RabbitMQ.
    3. Partition the PostgreSQL stock counter into $K$ bucket rows per deal to spread lock contention.
    4. Cache `GET /v1/deals/{dealId}` reads in Redis or CDN with 500ms TTL.

---

### Likely Tricky Interview Questions & How to Answer Them

1. **"Why did you use `FOR UPDATE SKIP LOCKED` in the expiry worker?"**
   - *Answer*: Without `SKIP LOCKED`, if two worker instances wake up simultaneously, Instance 2 would block waiting for the rows locked by Instance 1, or cause deadlocks. With `SKIP LOCKED`, Instance 1 locks batch A, and Instance 2 immediately grabs batch B without waiting or colliding.
2. **"Why does `GET /v1/claims/{claimId}` return 404 instead of 403 for unauthorized users?"**
   - *Answer*: Security best practice against resource enumeration (ID probing). Returning 403 reveals that a claim with that UUID exists. Returning 404 reveals zero information to attackers.
3. **"Why is money represented as integer paise instead of Double or Float?"**
   - *Answer*: IEEE-754 floating point numbers cannot accurately represent base-10 decimal fractions (e.g. `0.1 + 0.2 = 0.30000000000000004`). In financial systems, money must always be integer lowest currency units (paise/cents) to avoid rounding corruption.
