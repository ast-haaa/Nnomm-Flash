# Design Document: Nnomm Flash-Deal Claim Service

This document provides answers to the architectural and concurrency design questions based directly on the implementation in this repository.

---

### 1. Concurrent Claims for the Last Unit on Separate Instances

#### SQL Execution Walkthrough
When two customers on different instances claim the last available unit simultaneously, both requests invoke `ClaimService.executeClaimTransaction` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt#L166-L219)):

1. **Transaction Begin**: Each instance begins an explicit database transaction (`conn.autoCommit = false`).
2. **Pessimistic Row Lock on Deal**:
   ```sql
   SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
   FROM deals
   WHERE id = ? FOR UPDATE;
   ```
   ([DealRepository.kt](src/main/kotlin/com/nnomm/repository/DealRepository.kt#L42-L50))
3. **PostgreSQL Row-Level Locking Engine**:
   - The first transaction (Tx1) that arrives locks the tuple in PostgreSQL's shared memory lock table (`ExclusiveLock` on the deal tuple).
   - The second transaction (Tx2) attempts the same `FOR UPDATE`. PostgreSQL halts Tx2 in `WaitEvent: transactionid / tuple`, waiting until Tx1 terminates.
4. **Tx1 Checks and Updates**:
   - Tx1 reads `stock_available = 1`. Since `1 >= quantity (1)`, it proceeds:
     ```sql
     UPDATE deals SET stock_available = stock_available - 1 WHERE id = ? AND stock_available >= 1;
     INSERT INTO claims (...) VALUES (...);
     ```
     ([DealRepository.kt](src/main/kotlin/com/nnomm/repository/DealRepository.kt#L52-L65) and [ClaimRepository.kt](src/main/kotlin/com/nnomm/repository/ClaimRepository.kt#L19-L35))
   - Tx1 commits. Its tuple lock is released.
5. **Tx2 Resumes under Read Committed**:
   - Once Tx1 commits, PostgreSQL re-evaluates the row for Tx2 using the newly committed version (MVCC snapshot refresh in Read Committed).
   - Tx2 reads `stock_available = 0`.
   - The condition `deal.stock_available < quantity` fails, throwing `SoldOutException` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt#L186)).
   - Tx2 aborts/rolls back, decrement never runs, and 409 `SOLD_OUT` is returned.
   - Additionally, the database check constraint `CHECK (stock_available >= 0)` in [V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql#L9) physically forbids overselling at the storage engine level.

#### Why Kotlin Mutex or synchronized Won't Fix This
A Kotlin `Mutex`, `java.util.concurrent.locks.ReentrantLock`, or `synchronized` block is purely **in-memory within a single JVM process**. 
When running two (or $N$) service instances sharing a database:
- Instance 1's JVM and Instance 2's JVM have completely isolated heaps and memory spaces.
- Neither instance is aware of mutexes acquired by the other.
- Only a centralized coordination mechanism—specifically **PostgreSQL's row-level locks (`FOR UPDATE`) and atomic conditional writes**—serializes concurrent state transitions across distributed nodes.

---

### 2. Idempotency and Mid-Request Crashes

#### Scenario A: Service Commits Claim, Then Crashes Before Responding
1. The transaction created the claim in `claims` and saved the HTTP 201 response in `idempotency_keys` (`status_code = 201`, `response_body = <claim JSON>`) ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt#L118-L126)).
2. The network dropped or the pod restarted before delivering the HTTP response to the client.
3. The client mobile app retries with the exact same `Idempotency-Key`, `deal_id`, and `quantity`.
4. In `ClaimService.claimDeal`, the service executes:
   ```sql
   SELECT key, customer_id, deal_id, quantity, status_code, response_body, created_at, locked_until
   FROM idempotency_keys WHERE customer_id = ? AND key = ? FOR UPDATE;
   ```
5. It discovers `status_code != null`. It **replays** the stored response immediately with the response header `Idempotent-Replayed: true` ([ClaimController.kt](src/main/kotlin/com/nnomm/controller/ClaimController.kt#L45-L48)).
6. **Result**: Zero new claims created, zero stock lost, customer receives their original claim.

#### Scenario B: Service Crashes Just Before the Commit
1. The database connection terminates unexpectedly. Because PostgreSQL transactions are atomic, uncommitted state changes are automatically rolled back by the database. No row exists in `claims`, and `stock_available` is untouched.
2. However, the initial in-progress record in `idempotency_keys` exists with `locked_until = NOW() + 60s` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt#L74)).
3. **If retry arrives within 60 seconds**: The service detects `lockedUntil.isAfter(now)` and rejects with `409 REQUEST_IN_PROGRESS` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt#L91-L93)).
4. **If retry arrives after 60 seconds**: The crash recovery lease has expired. The service detects `lockedUntil <= now`, calls `idempotencyRepository.renewLock(...)` ([IdempotencyRepository.kt](src/main/kotlin/com/nnomm/repository/IdempotencyRepository.kt#L73-L89)), and safely re-executes the claim from scratch.

---

### 3. Payment Succeeded vs. Expiry Job Race Conditions

Both the payment webhook ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt#L47)) and the expiry worker ([ClaimRepository.kt](src/main/kotlin/com/nnomm/repository/ClaimRepository.kt#L80-L105)) lock the target claim row using `SELECT ... FOR UPDATE` (or `FOR UPDATE SKIP LOCKED`). There are 3 possible orderings:

| Ordering | Execution Flow | Final Claim Status | Final Stock Impact |
| :--- | :--- | :--- | :--- |
| **Ordering 1**<br>*(Webhook wins before expiry)* | Webhook acquires lock while `now < expires_at` and `status == RESERVED`. Transitions status to `PAID` ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt#L81)). Expiry job runs later; query `WHERE status = 'RESERVED'` skips this claim. | **`PAID`** | **0 change**<br>(Units remain claimed by customer) |
| **Ordering 2**<br>*(Expiry reaper wins before webhook)* | Expiry job locks claim, sets status to `EXPIRED`, and increments `stock_available` by quantity ([ClaimExpiryWorker.kt](src/main/kotlin/com/nnomm/service/ClaimExpiryWorker.kt#L51-L59)). Webhook acquires lock next, detects `status == EXPIRED`, and transitions status to `REFUND_PENDING` ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt#L121-L127)). | **`REFUND_PENDING`** | **+quantity returned to stock**<br>(Units made available to other customers) |
| **Ordering 3**<br>*(Webhook wins, but arrives after `expires_at`)* | Webhook acquires lock before reaper, but detects `now >= expires_at` while still `RESERVED`. It transitions status to `REFUND_PENDING` and restores stock ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt#L105-L111)). Reaper later skips because status is no longer `RESERVED`. | **`REFUND_PENDING`** | **+quantity returned to stock**<br>(Late payment refunded, units restored) |

**Invariant Guarantee**: In all 3 outcomes, `stock_available + active_claims == stock_total` holds perfectly without phantom vouchers or lost stock.

---

### 4. Webhook Cryptography, Timestamps, and Constant-Time Verification

#### Why Sign the Raw Body Instead of Parsed JSON?
JSON serialization is **non-deterministic**:
- Key ordering (`{"a":1,"b":2}` vs `{"b":2,"a":1}`)
- Whitespace and indentation (`{"a": 1}` vs `{"a":1}`)
- Numeric representation (`25000` vs `25000.0`)
- Unicode character escaping (`\u0026` vs `&`)

If the server parses the JSON into an object and re-serializes it to compute the HMAC, any minute syntactic difference changes the resulting SHA-256 hash. Computing the signature over the raw, unparsed byte stream ([CachedBodyHttpServletRequest.kt](src/main/kotlin/com/nnomm/security/PayloadSizeAndCachingFilter.kt#L18)) guarantees bit-for-bit identity with the sender.

#### What Does the Timestamp Protect Against?
The `t` parameter in `X-Payment-Signature: t=1792330000,v1=...` protects against **Replay Attacks**.
Without a timestamp check, an eavesdropper could capture a valid webhook request and replay it hours or days later. In [HmacService.kt](src/main/kotlin/com/nnomm/security/HmacService.kt#L27), we enforce:
```kotlin
if (abs(currentEpochSeconds - timestamp) > 300) {
    throw UnauthenticatedException("Webhook timestamp too old or too far in future (> 300s)")
}
```
Any event outside a 5-minute tolerance window is immediately rejected.

#### Why Compare Signatures in Constant Time?
Standard string comparison operators (`==` or `String.equals`) terminate early at the first mismatched character. An attacker with a low-latency network connection can send crafted signatures and measure microscopic variations in server response time (e.g. 10.1ms vs 10.3ms), deducing which bytes matched. This allows them to forge signatures byte-by-byte (a *timing attack*).
We use `MessageDigest.isEqual(computedHex.toByteArray(), v1.toByteArray())` in [HmacService.kt](src/main/kotlin/com/nnomm/security/HmacService.kt#L42), which executes in **constant time** regardless of where or whether a mismatch occurs.

---

### 5. Transaction Isolation Level

#### Level Used: `READ COMMITTED`
We configure PostgreSQL to its default `TRANSACTION_READ_COMMITTED` isolation level in [DatabaseFactory.kt](src/main/kotlin/com/nnomm/database/DatabaseFactory.kt#L59).

#### Why It Is Enough
While `READ COMMITTED` can be vulnerable to non-repeatable reads in non-locking queries, our critical mutating paths use explicit **pessimistic row locks** (`SELECT ... FOR UPDATE`):
- [DealRepository.kt](src/main/kotlin/com/nnomm/repository/DealRepository.kt#L43): Locks the target deal row during claims.
- [ClaimRepository.kt](src/main/kotlin/com/nnomm/repository/ClaimRepository.kt#L48): Locks the claim row during webhook processing.
- [ClaimRepository.kt](src/main/kotlin/com/nnomm/repository/ClaimRepository.kt#L91): Locks expired claims with `FOR UPDATE SKIP LOCKED`.

In PostgreSQL, `FOR UPDATE` writes an exclusive lock directly onto the row's tuple header. Even under `READ COMMITTED`, concurrent transactions attempting to read that row for update are forced to queue. When a queued transaction resumes, PostgreSQL's MVCC refreshes its snapshot to read the newly committed row state. This provides strict serialization on contentious rows **without the high abort/retry overhead of `SERIALIZABLE` isolation**.

#### Anomaly Under a Weaker Approach (e.g. Plain Reads without `FOR UPDATE`)
Without `FOR UPDATE`, two concurrent transactions could both execute:
```sql
SELECT stock_available FROM deals WHERE id = ?; -- Both read 1
-- Both compute 1 - 1 = 0
UPDATE deals SET stock_available = 0 WHERE id = ?;
```
This is a **Lost Update / Oversell Anomaly**: 2 vouchers are granted, but stock was only decremented by 1.

---

### 6. The Hardest-Working Constraint and Index

The constraint doing the most heavy lifting is the **Partial Unique Index** in [V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql#L31-L33):

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_customer_claim
ON claims (customer_id, deal_id)
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
```

#### What Breaks if Someone Drops It?
1. **The 1-Active-Claim Invariant Collapses**: A customer firing 10 concurrent requests with different `Idempotency-Key`s across different server instances would create 10 simultaneous claims.
2. Even though `deals` is row-locked, the customer check in `ClaimService` (`claimRepository.hasActiveClaim`) would execute concurrently across separate transactions before either had committed. Both would see zero active claims and both would insert rows.
3. Scalpers could hoard flash deals, violating: *"A customer can hold at most one active claim on a deal."*
4. **Why Partial?** Once a claim transitions to `EXPIRED` or `REFUND_PENDING`, it drops out of the partial index filter, allowing the customer to legitimately participate in future claims for that deal.

*Runner-up Constraint*: `CHECK (stock_available >= 0)` in [V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql#L9) guarantees that even if application logic fails, the database storage engine physically rejects negative stock.

---

### 7. 100× Traffic Surge Architecture (Handling Viral Deals)

#### What Fails First?
**Single-Row PostgreSQL Lock Contention**:
In a flash sale, all 50,000 requests per second target the exact same row in the `deals` table. 
- Even with fast transactions (2ms), serializing 50,000 transactions on one row queue causes extreme lock wait contention (`ExclusiveLock` wait queues).
- The HikariCP pool of 20-30 connections exhausts immediately, causing request timeouts (`503 Service Unavailable`).
- Database CPU reaches 100% simply managing transaction context switches and lock queues.

#### What to Change: The 3-Tier Flash Architecture

```
[50,000 RPS Traffic]
        │
        ▼
[Tier 1: Cloudflare / CDN Edge]
  - Static assets, deal metadata cached with 1s TTL (90% reads absorbed)
        │
        ▼
[Tier 2: Redis In-Memory Pre-Allocation Engine]
  - Lua script decrements in-memory token bucket: `deal:{id}:stock`
  - Lua script checks Redis Set: `deal:{id}:claimed_users` (1-claim rule)
  - Latency: 0.2ms per claim in single-threaded memory
  - Rejects 49,950 requests in memory without touching PostgreSQL!
        │ (Only 50 winners pass through)
        ▼
[Tier 3: Asynchronous Kafka Queue -> PostgreSQL Batch Persistence]
  - 50 winners pushed to Kafka topic: `claims.reserved` (partitioned by deal_id)
  - Dedicated worker pool consumes Kafka events and writes to PostgreSQL
  - Zero lock contention on database; 100% throughput reliability
```
