# Design Document: Nnomm Flash-Deal Claim Service

This document provides answers to the architectural and concurrency design questions based directly on the implementation in this repository.

---

### 1. Concurrent Claims for the Last Unit on Separate Instances

#### SQL Execution Walkthrough
When two customers on different instances claim the last available unit simultaneously, both requests invoke `ClaimService.executeClaimTransaction` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt)):

1. **Transaction Begin**: Each instance begins an explicit database transaction (`conn.autoCommit = false`).
2. **Pessimistic Row Lock on Deal**:
   ```sql
   SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at
   FROM deals
   WHERE id = ? FOR UPDATE;
   ```
   ([DealRepository.kt](src/main/kotlin/com/nnomm/repository/DealRepository.kt))
3. **PostgreSQL Row-Level Locking Engine**:
   - The first transaction (Tx1) that arrives locks the tuple in PostgreSQL's shared memory lock table (`ExclusiveLock` on the deal tuple).
   - The second transaction (Tx2) attempts the same `FOR UPDATE`. PostgreSQL halts Tx2 in `WaitEvent: transactionid / tuple`, waiting until Tx1 terminates.
4. **Tx1 Checks and Updates**:
   - Tx1 reads `stock_available = 1`. Since `1 >= quantity (1)`, it proceeds:
     ```sql
     UPDATE deals SET stock_available = stock_available - 1 WHERE id = ? AND stock_available >= 1;
     INSERT INTO claims (...) VALUES (...);
     ```
   - Tx1 commits. Its tuple lock is released.
5. **Tx2 Resumes under Read Committed**:
   - Once Tx1 commits, PostgreSQL re-evaluates the row for Tx2 using the newly committed version (MVCC snapshot refresh in Read Committed).
   - Tx2 reads `stock_available = 0`.
   - The condition `deal.stock_available < quantity` fails, throwing `SoldOutException` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt)).
   - Tx2 aborts/rolls back, decrement never runs, and 409 `SOLD_OUT` is returned.
   - Additionally, the database check constraint `CHECK (stock_available >= 0)` in `V1__init_schema.sql` physically forbids overselling at the storage engine level.

#### Why Kotlin Mutex or synchronized Won't Fix This
A Kotlin `Mutex`, `java.util.concurrent.locks.ReentrantLock`, or `synchronized` block is purely **in-memory within a single JVM process**. 
When running two (or $N$) service instances sharing a database:
- Instance 1's JVM and Instance 2's JVM have completely isolated heaps and memory spaces.
- Neither instance is aware of mutexes acquired by the other.
- Only a centralized coordination mechanism—specifically **PostgreSQL's row-level locks (`FOR UPDATE`) and atomic conditional writes**—serializes concurrent state transitions across distributed nodes.

---

### 2. Idempotency and Mid-Request Crashes

#### Scenario A: Service Commits Claim, Then Crashes Before Responding
1. The transaction created the claim in `claims` and saved the HTTP 201 response in `idempotency_keys` (`status_code = 201`, `response_body = <claim JSON>`) ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt)).
2. The network dropped or the pod restarted before delivering the HTTP response to the client.
3. The client mobile app retries with the exact same `Idempotency-Key`, `deal_id`, and `quantity`.
4. In `ClaimService.claimDeal`, the service executes:
   ```sql
   SELECT key, customer_id, deal_id, quantity, status_code, response_body, created_at, locked_until
   FROM idempotency_keys WHERE customer_id = ? AND key = ? FOR UPDATE;
   ```
   ([IdempotencyRepository.kt](src/main/kotlin/com/nnomm/repository/IdempotencyRepository.kt))
5. It discovers `status_code != null`. It **replays** the stored response immediately with the response header `Idempotent-Replayed: true`.
6. **Result**: Zero new claims created, zero stock lost, customer receives their original claim.

#### Scenario B: Service Crashes Just Before Commit
1. The transaction was still executing or about to commit when the container died.
2. PostgreSQL detects connection termination and **automatically aborts (rolls back)** the pending transaction.
3. The claim was never inserted into `claims`, and `stock_available` in `deals` was never decremented.
4. However, the initial in-progress record in `idempotency_keys` has `status_code = NULL` and `locked_until = created_at + 60s`.
5. If the client retries **within 60 seconds**:
   - `locked_until.isAfter(now)` is true. The service returns 409 `REQUEST_IN_PROGRESS` ([ClaimService.kt](src/main/kotlin/com/nnomm/service/ClaimService.kt)).
6. Once **60 seconds elapse**:
   - The crash lock expires. A subsequent retry detects `status_code IS NULL AND locked_until <= now`.
   - The service safely renews the lock (`renewLock`), re-executes the claim transaction, commits cleanly, and returns 201 Created.
7. **Result**: The idempotency key is never permanently blocked, and no duplicate claim is ever generated.

---

### 3. Payment Succeeded Webhook vs Expiry Job Racing in the Same Millisecond

Both the background `ClaimExpiryWorker` ([ClaimExpiryWorker.kt](src/main/kotlin/com/nnomm/service/ClaimExpiryWorker.kt)) and `PaymentWebhookService` ([PaymentWebhookService.kt](src/main/kotlin/com/nnomm/service/PaymentWebhookService.kt)) execute within a PostgreSQL transaction and lock the target claim row (`FOR UPDATE`). Thus, they are strictly serialized.

#### Ordering 1: Expiry Wins Row Lock First
1. **Expiry Transaction Runs**:
   - Condition `status = 'RESERVED' AND expires_at <= NOW()` matches.
   - Status transitions: `RESERVED -> EXPIRED`.
   - Units returned to stock: `stock_available = stock_available + quantity`.
   - Expiry commits and releases row lock.
2. **Webhook Transaction Runs Next**:
   - Webhook acquires lock on the claim.
   - Reads status: `EXPIRED`.
   - Per specification (payment arrived after expiry), status transitions: `EXPIRED -> REFUND_PENDING`.
   - Units were already returned to stock during the expiry step, so units stay in stock (no additional stock modification).
   - Webhook commits.
- **Final Claim Status**: `REFUND_PENDING`
- **Final Stock**: Units returned to `stock_available` (`stock_available` reflects full unreserved units).

#### Ordering 2: Webhook Wins Row Lock First (Before `expires_at` Has Passed)
1. **Webhook Transaction Runs**:
   - Reads status `RESERVED` and `now < expires_at`.
   - Amount matches: status transitions `RESERVED -> PAID`.
   - Units remain held (`stock_available` unchanged).
   - Webhook commits and releases row lock.
2. **Expiry Transaction Runs Next**:
   - Evaluates `WHERE status = 'RESERVED' AND expires_at <= NOW()`.
   - The claim is now `PAID`, so it does not match. Expiry does nothing.
- **Final Claim Status**: `PAID`
- **Final Stock**: Units stay sold (`stock_available` decremented).

#### Ordering 3: Webhook Wins Row Lock First (After `expires_at` Has Passed)
1. **Webhook Transaction Runs**:
   - Reads status `RESERVED` but `now >= expires_at`.
   - Webhook recognizes late payment:
     - Returns units to stock: `stock_available = stock_available + quantity`.
     - Status transitions: `RESERVED -> REFUND_PENDING`.
   - Webhook commits.
2. **Expiry Transaction Runs Next**:
   - Claim status is already `REFUND_PENDING` (not `RESERVED`). Expiry ignores it.
- **Final Claim Status**: `REFUND_PENDING`
- **Final Stock**: Units returned to `stock_available`.

**Invariant outcome**: In all possible orderings, the stock invariant `stock_available + sum(active units) == stock_total` holds at every millisecond.

---

### 4. Webhook Security: Raw Body, Timestamps, and Constant-Time Comparison

1. **Why Signature Must Cover Raw Bytes Instead of Parsed JSON**:
   - Cryptographic hashes (HMAC-SHA256) operate on exact byte sequences.
   - JSON deserialization is non-deterministic: key order can change (`{"a":1,"b":2}` vs `{"b":2,"a":1}`), whitespace/newlines differ, and floating-point/numeric formats can normalize differently.
   - Hashing parsed or re-serialized JSON would result in a different hash digest, failing verification for valid webhooks. Therefore, the raw HTTP request bytes must be captured before any parsing ([WebhookRoutes.kt](src/main/kotlin/com/nnomm/routes/WebhookRoutes.kt), [HmacService.kt](src/main/kotlin/com/nnomm/security/HmacService.kt)).

2. **What the Timestamp ($t$) Protects Against**:
   - It prevents **Replay Attacks**. An eavesdropper or malicious actor who intercepts a signed webhook could replay the exact same HTTP request later.
   - By enforcing $|now - t| \le 300$ seconds ([HmacService.kt](src/main/kotlin/com/nnomm/security/HmacService.kt)), stale captured requests are rejected. (Coupled with the `event_id` uniqueness check in [PaymentEventRepository.kt](src/main/kotlin/com/nnomm/repository/PaymentEventRepository.kt), duplicate processing is completely prevented).

3. **Why Compare Signatures in Constant Time**:
   - Standard string equality (`==` or `.equals()`) terminates on the first mismatching character.
   - An attacker can measure nano-second response time differences to incrementally guess the HMAC byte by byte (a **timing attack**).
   - `MessageDigest.isEqual(...)` executes in constant time relative to signature length ([HmacService.kt](src/main/kotlin/com/nnomm/security/HmacService.kt)), eliminating timing side-channels.

---

### 5. Transaction Isolation Level and Anomalies

- **Isolation Level Used**: `READ COMMITTED` (PostgreSQL default).
- **Why It Is Sufficient**:
  1. All concurrent mutations on `deals`, `claims`, and `idempotency_keys` utilize explicit row-level locks (`FOR UPDATE`) or atomic conditional updates (`UPDATE ... WHERE stock_available >= quantity`).
  2. In PostgreSQL, when a transaction attempts to lock or update a row that is already locked by another transaction, it waits. Under `READ COMMITTED`, once the first transaction commits, the waiting query reads the latest committed tuple version and continues.
  3. Combined with PostgreSQL constraints (CHECK constraints and partial unique indexes), `READ COMMITTED` with pessimistic locking achieves complete serialization of conflicting writes without the serialization failure aborts (`40001 serialization_failure`) typical of `REPEATABLE READ` or `SERIALIZABLE`.
- **Anomaly Under Weaker Approach (e.g. Read Uncommitted without Row Locks)**:
  - **Lost Updates / Dirty Reads**: If two transactions read `stock_available = 1` concurrently without locks and both subtract 1, both update `stock_available = 0`, resulting in **overselling** (2 units sold when only 1 existed).

---

### 6. The Most Critical Constraint and Index

- **Constraint**: The Partial Unique Index:
  ```sql
  CREATE UNIQUE INDEX uq_active_customer_claim
  ON claims (customer_id, deal_id)
  WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
  ```
  ([V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql))
- **Why It Does the Most Work**:
  - It enforces Rule 2 ("One active claim per customer per deal") deterministically across distributed instances without requiring distributed locks or table locks.
  - As soon as a claim transitions to `EXPIRED` or `REFUND_PENDING`, it drops out of the index condition, automatically permitting the customer to claim again without any manual cleanup.
- **What Breaks If Dropped**:
  - Under heavy concurrency (e.g., 20 requests sent in the same millisecond by the same customer with different keys), race conditions between `hasActiveClaim` check and `INSERT` would allow a single customer to hoard multiple reserved units, violating business invariant 2.

---

### 7. 100× Traffic Surge (Viral Flash Deal)

#### What Fails First
1. **Database Row Lock Contention on the `deals` Row**:
   - Hundreds of concurrent transactions waiting on `SELECT ... FOR UPDATE` on a single deal ID cause lock queue saturation in PostgreSQL, consuming worker threads and connection pool slots (`HikariCP` exhaustion).
2. **Connection Pool Starvation**:
   - Requests waiting for deal row locks hold database connections open, causing connection timeouts for other endpoints (`GET /v1/deals/{id}`).

#### How to Scale for 100×
1. **Redis / Distributed In-Memory Reservation**:
   - Decouple stock deduction from the relational DB. Use Redis with an atomic Lua script (`DECRBY` with check `stock >= 0`) or Redis cell rate limiting.
   - Once reserved in Redis, emit an event to a Kafka/RabbitMQ queue to asynchronously write the claim to PostgreSQL.
2. **Partitioned / Striped Stock Counter in PostgreSQL**:
   - If staying on PostgreSQL, split deal stock across $K$ sub-buckets (e.g., 10 rows of 5 units each). Transactions pick a random bucket to lock, reducing lock contention by a factor of $K$.
3. **Read Replicas & Caching**:
   - Route `GET /v1/deals/{dealId}` to read replicas or a short TTL cache (e.g., Caffeine/Redis with 500ms TTL), preventing read traffic from starving write connections.
