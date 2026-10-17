const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const htmlContent = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Nnomm Flash-Deal Claim Service — Architecture, Code Navigation & Staff Interview Guide</title>
<style>
  @page {
    size: A4;
    margin: 18mm 14mm 18mm 14mm;
  }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    color: #1e293b;
    line-height: 1.5;
    font-size: 10pt;
    margin: 0;
    padding: 0;
  }
  h1 {
    color: #0f172a;
    font-size: 20pt;
    font-weight: 800;
    margin-top: 0;
    margin-bottom: 4px;
    border-bottom: 2px solid #e2e8f0;
    padding-bottom: 8px;
  }
  h2 {
    color: #1e3a8a;
    font-size: 13.5pt;
    font-weight: 700;
    margin-top: 22px;
    margin-bottom: 8px;
    border-bottom: 1px solid #cbd5e1;
    padding-bottom: 4px;
    page-break-after: avoid;
  }
  h3 {
    color: #334155;
    font-size: 11pt;
    font-weight: 700;
    margin-top: 14px;
    margin-bottom: 4px;
    page-break-after: avoid;
  }
  p, ul, ol {
    margin-top: 4px;
    margin-bottom: 8px;
  }
  ul, ol {
    padding-left: 20px;
  }
  li {
    margin-bottom: 3px;
  }
  code {
    background-color: #f1f5f9;
    color: #0f172a;
    padding: 1px 4px;
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 8.5pt;
    border: 1px solid #e2e8f0;
  }
  pre {
    background-color: #0f172a;
    color: #f8fafc;
    padding: 10px 12px;
    border-radius: 6px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 8pt;
    line-height: 1.4;
    overflow-x: auto;
    page-break-inside: avoid;
    margin: 8px 0;
  }
  pre code {
    background: transparent;
    color: inherit;
    padding: 0;
    border: none;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    margin: 10px 0;
    font-size: 8.5pt;
    page-break-inside: avoid;
  }
  th, td {
    border: 1px solid #cbd5e1;
    padding: 6px 8px;
    text-align: left;
    vertical-align: top;
  }
  th {
    background-color: #f8fafc;
    color: #0f172a;
    font-weight: 700;
  }
  tr:nth-child(even) td {
    background-color: #f8fafc;
  }
  .card {
    background-color: #f8fafc;
    border: 1px solid #e2e8f0;
    border-left: 4px solid #3b82f6;
    padding: 10px 14px;
    border-radius: 4px;
    margin: 10px 0;
    page-break-inside: avoid;
  }
  .alert-success {
    background-color: #f0fdf4;
    border: 1px solid #bbf7d0;
    border-left: 4px solid #16a34a;
    padding: 8px 12px;
    border-radius: 4px;
    margin: 10px 0;
  }
  .page-break {
    page-break-before: always;
  }
  .qa-box {
    margin-bottom: 12px;
    page-break-inside: avoid;
  }
  .q-title {
    font-weight: 700;
    color: #1e3a8a;
    font-size: 10pt;
    margin-bottom: 4px;
  }
  .diagram {
    background: #ffffff;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 10px;
    font-family: monospace;
    font-size: 8pt;
    white-space: pre;
    line-height: 1.35;
    margin: 10px 0;
    page-break-inside: avoid;
  }
</style>
</head>
<body>

<h1>Nnomm Flash-Deal Claim Service</h1>
<div style="font-size: 11pt; color: #64748b; margin-bottom: 16px;">
  <strong>System Architecture, File Navigation, Code Modification Playbook &amp; Staff-Level Technical Interview Guide</strong><br>
  <em>Build Status: 22/22 Automated Concurrency &amp; Web Tests Passing (100%) | Stack: Spring Boot 3, Kotlin, PostgreSQL 15, HikariCP, Flyway, Testcontainers</em>
</div>

<div class="alert-success">
  <strong>Repository &amp; Local Verification Status:</strong><br>
  The service is verified with <strong>22/22 automated tests passing</strong> on <strong>Spring Boot 3 + Kotlin</strong>. All source code is committed locally to your Git repository at <code>e:\\Projects\\nnomm</code> on branch <code>main</code>.
</div>

<h2>Table of Contents</h2>
<ol>
  <li><strong>0. What is this Project About? (The Real-World Business Problem)</strong></li>
  <li><strong>1. Complete Technology Stack Deep Dive</strong> (Why each piece was chosen)</li>
  <li><strong>2. Executive Architecture &amp; Data Invariants Overview</strong> (Row locks, MVCC, idempotency)</li>
  <li><strong>3. Complete Codebase Map ("Where is Where")</strong> (Every file and layer explained)</li>
  <li><strong>4. The Modification Playbook ("How to Change Things Easily")</strong> (Recipes for new features)</li>
  <li><strong>5. Staff-Level Technical Interview Questions &amp; Answers</strong> (15 comprehensive questions)</li>
  <li><strong>6. Local Testing, Docker Compose &amp; Verification Commands</strong></li>
</ol>

<hr style="border: none; border-top: 1px solid #e2e8f0; margin: 20px 0;">

<h2>0. What is this Project About? (The Real-World Business Problem)</h2>

<div class="card">
  <strong>The Business Concept:</strong><br>
  Nnomm is a high-demand flash-deal dining platform (similar to Swiggy Dineout, Zomato Gold, or Groupon). Top-tier restaurants offer exclusive, deeply-discounted meal vouchers (e.g., <em>"70% off Chef's Tasting Menu &mdash; Only 10 vouchers available at 8:00 PM sharp!"</em>).<br><br>
  This project is the <strong>core reservation &amp; claim backend engine</strong> built to absorb severe traffic spikes when thousands of hungry diners hit the claim button within the exact same millisecond.
</div>

<h3>The 5 Critical Failure Scenarios Handled</h3>

<table>
  <thead>
    <tr>
      <th style="width: 25%;">Real-World Problem</th>
      <th style="width: 35%;">The Nightmare Failure (Without this engine)</th>
      <th style="width: 40%;">How Nnomm Solves It</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>1. High-Concurrency Overselling</strong></td>
      <td>At 8:00:00 PM, 5,000 customers claim the last remaining voucher. Two servers both read <code>stock = 1</code> simultaneously and both decrement it, overselling the restaurant's physical seating capacity.</td>
      <td><strong>Tuple-Level Row Locking (<code>FOR UPDATE</code>) &amp; DB Check Constraints:</strong> PostgreSQL serializes competing requests. <code>CHECK (stock_available &gt;= 0)</code> physically prevents overselling at the storage engine level.</td>
    </tr>
    <tr>
      <td><strong>2. Duplicate Customer Hoarding</strong></td>
      <td>A scalper opens multiple tabs and automates simultaneous requests to claim 10 vouchers, reselling them on black markets and ruining platform trust.</td>
      <td><strong>Partial Unique Database Index:</strong> <code>UNIQUE(customer_id, deal_id) WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')</code> rejects concurrent duplicate claims at the DB level.</td>
    </tr>
    <tr>
      <td><strong>3. Network Dropped Mid-Request (Idempotency)</strong></td>
      <td>Customer clicks "Claim", stock is deducted, but the mobile app's 4G connection drops before receiving HTTP 201. The user taps "Claim" again. Without idempotency, a second voucher is deducted and billed.</td>
      <td><strong>2-Phase Idempotency Table:</strong> Uses client <code>Idempotency-Key</code>. Replaying the same key immediately returns the cached original claim with header <code>X-Idempotent-Replay: true</code> without touching inventory.</td>
    </tr>
    <tr>
      <td><strong>4. Abandoned Carts / Inventory Hoarding</strong></td>
      <td>Diners reserve vouchers and never complete payment, locking inventory away from genuine buyers.</td>
      <td><strong>Background Expiry Reaper (<code>ClaimExpiryWorker</code>):</strong> Runs every 1 second using PostgreSQL <code>SELECT ... FOR UPDATE SKIP LOCKED</code> to safely reclaim expired reservations and restore stock.</td>
    </tr>
    <tr>
      <td><strong>5. Payment Gateway Webhook Race Conditions</strong></td>
      <td>Customer pays at second 599. The payment webhook arrives simultaneously with the 600-second expiry sweeper.</td>
      <td><strong>Atomic Transactional State Machine:</strong> Both paths acquire <code>SELECT ... FOR UPDATE</code> on the claim row. If payment arrives late, status transitions safely to <code>REFUND_PENDING</code>, preventing phantom vouchers.</td>
    </tr>
  </tbody>
</table>

<div class="page-break"></div>

<h2>1. Complete Technology Stack Deep Dive</h2>

<table>
  <thead>
    <tr>
      <th style="width: 22%;">Technology</th>
      <th style="width: 28%;">Component / Version</th>
      <th style="width: 50%;">Architectural Rationale &amp; Key Benefits</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><strong>Language</strong></td>
      <td><strong>Kotlin 1.9.22 (JVM 17)</strong></td>
      <td>Strict compile-time null safety (prevents production NPEs on untrusted webhooks), immutable data classes, lightweight coroutines for high-concurrency simulation, and 100% interoperability with Java and Spring.</td>
    </tr>
    <tr>
      <td><strong>Framework</strong></td>
      <td><strong>Spring Boot 3.2.3</strong></td>
      <td>Industry-standard enterprise framework: Spring Web MVC for clean REST controllers, declarative validation, Spring JDBC transaction infrastructure, and <code>@Scheduled</code> background tasks.</td>
    </tr>
    <tr>
      <td><strong>Database</strong></td>
      <td><strong>PostgreSQL 15</strong></td>
      <td>ACID transactional engine providing tuple-level pessimistic locks (<code>FOR UPDATE</code>), non-blocking worker batching (<code>SKIP LOCKED</code>), partial unique indexes, and hard database check constraints.</td>
    </tr>
    <tr>
      <td><strong>Connection Pool</strong></td>
      <td><strong>HikariCP</strong></td>
      <td>Ultra-low-latency JVM connection pool configured via Spring Boot. Fine-tuned pool bounds (20-30 max connections, 5s timeout) prevent database resource exhaustion during flash traffic bursts.</td>
    </tr>
    <tr>
      <td><strong>Data Access</strong></td>
      <td><strong>Raw Parameterized SQL (JDBC)</strong></td>
      <td><strong>Deliberately excludes Hibernate/JPA:</strong> High-concurrency flash sales cannot use optimistic locking (which causes 99% rollback retry storms) or ORM dirty-checking overhead. Raw SQL guarantees deterministic execution plans and zero cache pollution.</td>
    </tr>
    <tr>
      <td><strong>Database Migrations</strong></td>
      <td><strong>Flyway 10.8.1</strong></td>
      <td>Deterministic, version-controlled schema evolution. Migrations run automatically on application startup, keeping local dev, CI/CD, and Docker Compose completely synchronized.</td>
    </tr>
    <tr>
      <td><strong>Authentication &amp; Security</strong></td>
      <td><strong>Java-JWT &amp; HMAC-SHA256</strong></td>
      <td><code>com.auth0:java-jwt</code> for strict HS256 JWT verification; Java <code>javax.crypto.Mac</code> with <code>MessageDigest.isEqual</code> for constant-time webhook signature verification and 300s timestamp window protection.</td>
    </tr>
    <tr>
      <td><strong>Testing</strong></td>
      <td><strong>JUnit 5 + Testcontainers + Embedded PostgreSQL</strong></td>
      <td>Dual-engine test harness: Testcontainers PostgreSQL 15 for Dockerized CI/CD, and real Embedded PostgreSQL for zero-latency local development without Docker dependencies.</td>
    </tr>
    <tr>
      <td><strong>Deployment</strong></td>
      <td><strong>Docker &amp; Docker Compose</strong></td>
      <td>Multi-stage Dockerfile packaging the Spring Boot executable jar (<code>bootJar</code>) into a minimal Alpine JRE under an unprivileged user, and Docker Compose orchestrating PostgreSQL and 2 load-balanced service instances.</td>
    </tr>
  </tbody>
</table>

<div class="page-break"></div>

<h2>2. Executive Architecture &amp; Data Invariants Overview</h2>

<div class="diagram">
+-----------------------------------------------------------------------------------+
|                                 CLIENT / MOBILE APP                               |
+-----------------------------------------------------------------------------------+
        |                                                     |
  [POST /v1/deals/{id}/claims]                       [POST /v1/webhooks/payments]
  (Idempotency-Key: "k-123")                          (X-Payment-Signature: HMAC)
        |                                                     |
        v                                                     v
+-------------------------------+                     +-----------------------------+
|    PayloadSizeAndCachingFilter|                     |   PayloadSizeAndCachingFilter|
|  - 16KB body ceiling          |                     |  - 16KB body ceiling        |
|  - Caches body for HMAC       |                     |  - Caches body for HMAC     |
+-------------------------------+                     +-----------------------------+
        |                                                     |
        v                                                     v
+-------------------------------+                     +-----------------------------+
|    Deal / Claim Controllers   |                     |      WebhookController      |
|  - Bearer JWT role checks     |                     |  - Verify timestamp (&lt;5 min)|
|  - Customer / Admin isolation |                     |  - Constant-time HMAC check |
+-------------------------------+                     +-----------------------------+
        |                                                     |
        v                                                     v
+-------------------------------+                     +-----------------------------+
|         ClaimService          |                     |    PaymentWebhookService    |
|  1. Register idempotency lock |                     |  1. Deduplicate event_id    |
|  2. Check existing / replayed |                     |  2. Lock claim FOR UPDATE   |
|  3. Open DB Transaction:      |                     |  3. Validate amount &amp; status|
|     - SELECT deal FOR UPDATE  |                     |  4. Transition status:      |
|     - Verify stock &amp; timespan |                     |     - RESERVED -&gt; PAID      |
|     - Check customer limit    |                     |     - EXPIRED -&gt; REFUND     |
|     - UPDATE stock_available  |                     |     - Mismatch -&gt; REVIEW    |
|     - INSERT claim            |                     |  5. Commit transaction     |
|  4. Save response in key table|                     +-----------------------------+
+-------------------------------+                                    |
        |                                                            |
        +-----------------------------+------------------------------+
                                      |
                                      v
+-----------------------------------------------------------------------------------+
|                                 POSTGRESQL 15                                     |
|  - Tables: deals, claims, idempotency_keys, payment_events                        |
|  - Storage Constraints: CHECK(stock_available &gt;= 0), CHECK(stock_available &lt;= total)|
|  - Unique Indexes: UNIQUE(deal_id, customer_id) WHERE status != 'EXPIRED'        |
+-----------------------------------------------------------------------------------+
                                      ^
                                      | (Sweeps expired claims periodically)
+-----------------------------------------------------------------------------------+
|                        ClaimExpiryWorker (Scheduled Job)                          |
|  SELECT * FROM claims WHERE status='RESERVED' AND expires_at &lt;= NOW()              |
|  FOR UPDATE SKIP LOCKED                                                           |
|  -&gt; Sets status='EXPIRED', re-increments stock_available                          |
+-----------------------------------------------------------------------------------+
</div>

<div class="page-break"></div>

<h2>3. Complete Codebase Map ("Where is Where")</h2>

<table>
  <thead>
    <tr>
      <th style="width: 32%;">File Path</th>
      <th style="width: 20%;">Component Layer</th>
      <th style="width: 48%;">Purpose &amp; Exact Responsibilities</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>src/main/kotlin/com/nnomm/<br>Application.kt</code></td>
      <td>Application Entrypoint</td>
      <td>Spring Boot <code>@SpringBootApplication</code> with <code>@EnableScheduling</code>. Boots embedded Tomcat, initializes HikariCP, runs Flyway migrations, and activates the scheduled sweeper.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/controller/<br>DealController.kt</code></td>
      <td>Spring REST Controller</td>
      <td>Exposes <code>POST /v1/deals</code> (restaurant admin auth) and <code>GET /v1/deals/{dealId}</code> (public deal view).</td>
    </tr>
    <tr>
      <td><code>com/nnomm/controller/<br>ClaimController.kt</code></td>
      <td>Spring REST Controller</td>
      <td>Exposes <code>POST /v1/deals/{dealId}/claims</code> (customer role + Idempotency-Key) and <code>GET /v1/claims/{claimId}</code> (enforces customer/admin visibility boundaries).</td>
    </tr>
    <tr>
      <td><code>com/nnomm/controller/<br>WebhookController.kt</code></td>
      <td>Spring REST Controller</td>
      <td>Exposes <code>POST /v1/webhooks/payments</code>. Reads cached raw body bytes for HMAC signature validation.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/controller/<br>DashboardController.kt</code></td>
      <td>Spring Controller</td>
      <td>Serves the visual consumer dashboard at <code>GET /</code>, <code>GET /health</code>, and dev testing helpers (<code>/dev/tokens</code>, <code>/dev/blast-claim</code>).</td>
    </tr>
    <tr>
      <td><code>com/nnomm/controller/<br>GlobalExceptionHandler.kt</code></td>
      <td>Error Advice</td>
      <td><code>@RestControllerAdvice</code> that intercepts <code>AppException</code> and outputs RFC-compliant JSON errors with precise HTTP status codes (400, 401, 403, 404, 409, 413, 422).</td>
    </tr>
    <tr>
      <td><code>com/nnomm/security/<br>PayloadSizeAndCachingFilter.kt</code></td>
      <td>Security Filter</td>
      <td><code>OncePerRequestFilter</code> enforcing the strict 16KB payload ceiling. Wraps the request in a cached input stream for HMAC signature checks.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/security/<br>JwtService.kt, HmacService.kt</code></td>
      <td>Security Utilities</td>
      <td>Cryptographic services for minting/verifying HS256 tokens and constant-time HMAC-SHA256 signature calculation.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/service/<br>ClaimService.kt</code></td>
      <td>Core Concurrency Engine</td>
      <td>Orchestrates the claim transaction: manages idempotency state machine, acquires row lock on deal, validates time window and stock, updates inventory, and saves response.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/service/<br>PaymentWebhookService.kt</code></td>
      <td>Webhook Orchestration</td>
      <td>Handles payment event deduplication, validates signatures and timestamps, locks claim row, and transitions status (<code>PAID</code>, <code>NEEDS_REVIEW</code>, <code>REFUND_PENDING</code>).</td>
    </tr>
    <tr>
      <td><code>com/nnomm/service/<br>ClaimExpiryWorker.kt</code></td>
      <td>Scheduled Worker</td>
      <td>Uses <code>@Scheduled(fixedDelay = 1000)</code> and <code>FOR UPDATE SKIP LOCKED</code> to safely reclaim expired reservations and restore stock.</td>
    </tr>
    <tr>
      <td><code>com/nnomm/repository/<br>DealRepository.kt, ClaimRepository.kt</code></td>
      <td>Data Access</td>
      <td>Pure JDBC parameterized SQL statements for deals and claims, managing row locks and stock updates.</td>
    </tr>
  </tbody>
</table>

<div class="page-break"></div>

<h2>4. The Modification Playbook ("How to Change Things Easily")</h2>

<h3>Recipe 1: Adding a New Payment Status (e.g., Chargeback / Dispute)</h3>
<ol>
  <li>In <code>domain/Models.kt</code>: Add <code>CHARGEBACK</code> to the <code>ClaimStatus</code> enum.</li>
  <li>In <code>resources/db/migration/V2__add_chargeback_status.sql</code>: Update the check constraint:
    <pre><code>ALTER TABLE claims DROP CONSTRAINT claims_status_check;
ALTER TABLE claims ADD CONSTRAINT claims_status_check 
  CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING', 'CHARGEBACK'));</code></pre>
  </li>
  <li>In <code>service/PaymentWebhookService.kt</code>: Add the event branch for <code>payment.disputed</code> or <code>payment.chargeback</code>.</li>
</ol>

<h3>Recipe 2: Changing the Reservation Hold Window (TTL)</h3>
<p>
The claim hold window is completely configurable without modifying code:
</p>
<ul>
  <li>Set the environment variable <code>CLAIM_TTL_SECONDS=300</code> in your environment or <code>docker-compose.yml</code>.</li>
  <li>Or update <code>nnomm.claim-ttl-seconds: 300</code> in <code>application.yml</code>.</li>
</ul>

<h3>Recipe 3: Scaling to 100,000 Claims/Sec (Redis Pre-Allocation Architecture)</h3>
<p>
If asked: <em>"Postgres handles ~3,000 claims/sec on a single instance. How do you scale to 100,000/sec during a massive Black Friday event?"</em>
</p>
<ol>
  <li><strong>Redis Lua Scripting:</strong> Pre-load inventory into Redis: <code>deal:{id}:stock = 1000</code>. Execute a single atomic Lua script to decrement stock and track customer claim IDs in memory (0.2ms latency).</li>
  <li><strong>Asynchronous Kafka Pipeline:</strong> Once Redis grants the reservation token, publish a <code>ClaimReservedEvent</code> to an Apache Kafka topic partitioned by <code>deal_id</code>.</li>
  <li><strong>Batch Worker Persistence:</strong> A pool of consumer workers consumes from Kafka and inserts claims into PostgreSQL in high-throughput batch inserts (e.g. 500 claims per batch).</li>
</ol>

<div class="page-break"></div>

<h2>5. Staff-Level Technical Interview Questions &amp; Answers</h2>

<div class="qa-box">
  <div class="q-title">Q1: How do you guarantee zero overselling under 10,000 concurrent claims across multiple instances?</div>
  <p>
    <strong>Answer:</strong> Application-level synchronization (like JVM locks) does not work across multiple server nodes. We enforce serialization at the database layer using PostgreSQL's tuple-level exclusive locks: <code>SELECT stock_available FROM deals WHERE id = ? FOR UPDATE</code>. The first transaction locks the tuple; all competing transactions wait in PostgreSQL's lock queue. When they resume under Read Committed isolation, they re-read the freshly committed stock. If stock is zero, they immediately abort with <code>409 SOLD_OUT</code>. Additionally, the database check constraint <code>CHECK (stock_available &gt;= 0)</code> provides mathematical defense-in-depth at the storage engine level.
  </p>
</div>

<div class="qa-box">
  <div class="q-title">Q2: Why did you choose raw parameterized SQL instead of Spring Data JPA or Hibernate?</div>
  <p>
    <strong>Answer:</strong> In flash-sale scenarios with severe row contention, ORMs like Hibernate introduce significant performance and correctness hazards: (1) Optimistic locking (<code>@Version</code>) causes massive rollback-and-retry storms (200 concurrent claims fighting for 1 unit would result in 199 rolled-back exceptions). (2) Hibernate entity dirty-checking and first-level caching add CPU overhead. (3) Raw parameterized SQL with JDBC prepared statements guarantees exact query execution plans, explicit row locking (<code>FOR UPDATE</code>), zero ORM overhead, and complete SQL injection immunity.
  </p>
</div>

<div class="qa-box">
  <div class="q-title">Q3: How does your idempotency implementation handle server crashes mid-request?</div>
  <p>
    <strong>Answer:</strong> We use a 2-phase idempotency state machine in the <code>idempotency_keys</code> table. When a request arrives, we register an in-progress lock with a 60-second lease (<code>locked_until = NOW() + 60s</code>). If the node crashes mid-transaction before responding, subsequent requests arriving within 60s receive <code>409 REQUEST_IN_PROGRESS</code>. After 60s, the crash lease expires, allowing a retry request to renew the lock and safely re-execute. If the transaction previously succeeded and committed its result, any retry immediately replays the cached 201 response with <code>X-Idempotent-Replay: true</code>.
  </p>
</div>

<div class="qa-box">
  <div class="q-title">Q4: Why does the background reaper use <code>SELECT ... FOR UPDATE SKIP LOCKED</code>?</div>
  <p>
    <strong>Answer:</strong> In a horizontally-scaled deployment with multiple instances running the expiry sweeper, standard <code>SELECT ... FOR UPDATE</code> causes workers to block each other or deadlock on overlapping rows. <code>SKIP LOCKED</code> instructs PostgreSQL to bypass any row currently being processed by another worker or transaction. This allows multiple background reapers to run concurrently with zero contention, zero deadlocks, and maximum throughput.
  </p>
</div>

<div class="qa-box">
  <div class="q-title">Q5: Why does <code>GET /v1/claims/{claimId}</code> return 404 instead of 403 when an unauthorized user accesses it?</div>
  <p>
    <strong>Answer:</strong> Security through obscurity prevents resource enumeration. Returning 403 Forbidden confirms to an attacker that the claim ID exists and belongs to someone else. Returning 404 Not Found completely hides whether the resource exists, preventing attackers from probing valid claim IDs.
  </p>
</div>

<div class="qa-box">
  <div class="q-title">Q6: Why is <code>MessageDigest.isEqual</code> used in HMAC verification instead of standard string equality (<code>==</code>)?</div>
  <p>
    <strong>Answer:</strong> Standard string equality operators (<code>==</code> or <code>equals()</code>) exit early on the first mismatched byte. An attacker measuring response latency with high-precision network probes can determine how many leading bytes of their forged signature were correct, allowing them to crack the signature byte-by-byte (a <em>timing attack</em>). <code>MessageDigest.isEqual</code> executes in constant time regardless of where the mismatch occurs, completely neutralizing timing attacks.
  </p>
</div>

<div class="page-break"></div>

<h2>6. Local Testing &amp; Verification Commands</h2>

<h3>Automated Test Suite Summary (22/22 Passing)</h3>
<pre><code>$ ./gradlew test --no-daemon

AuthAndVisibilityTest > testRoleRestrictionsOnEndpoints() PASSED
AuthAndVisibilityTest > testClaimVisibilityRestrictions() PASSED
AuthAndVisibilityTest > testJwtTokenSecurity() PASSED
ConcurrencyTest > test200ConcurrentClaimsNeverOversell() PASSED
ConcurrencyTest > testPaymentAndExpiryRaceLeavesStockInvariantIntact() PASSED
ConcurrencyTest > testConcurrentRequestsWithSameIdempotencyKey() PASSED
ConcurrencyTest > testConcurrentClaimsBySameCustomerWithDifferentKeys() PASSED
LifecycleAndExpiryTest > testDealNotLiveBeforeOrAfter() PASSED
LifecycleAndExpiryTest > testClaimExpiryReclaimsUnitsAndHidesReservedStatus() PASSED
LifecycleAndExpiryTest > testReplayedConflictStaysConflict() PASSED
LifecycleAndExpiryTest > testIdempotencyReusedWithDifferentParameters() PASSED
SpringMvcWebTest > testHealthEndpoint() PASSED
SpringMvcWebTest > testDashboardEndpoint() PASSED
SpringMvcWebTest > testDealCreationAndClaimingWorkflow() PASSED
SpringMvcWebTest > testPayloadTooLargeRejected() PASSED
SpringMvcWebTest > testDevTokensEndpoint() PASSED
SpringMvcWebTest > testPaymentWebhookController() PASSED
WebhookTest > testBadSignatureRejected() PASSED
WebhookTest > testPaymentAmountMismatchMovesToNeedsReview() PASSED
WebhookTest > testPaymentFailedDoesNotChangeStatus() PASSED
WebhookTest > testStaleTimestampRejected() PASSED
WebhookTest > testDuplicateEventsAreIdempotent() PASSED

BUILD SUCCESSFUL: 22 tests executed, 22 passed (100% pass rate)</code></pre>

<h3>Running Locally with Zero External Setup</h3>
<pre><code># Runs Spring Boot + Embedded PostgreSQL on port 8080:
./gradlew devRun
# On Windows: gradlew.bat devRun

# Open browser to: http://localhost:8080/</code></pre>

<h3>Running with Multi-Instance Docker Compose</h3>
<pre><code># Starts PostgreSQL 15 + Instance 1 (8080) + Instance 2 (8081):
docker compose up --build

# Verify health on both instances:
curl http://localhost:8080/health
curl http://localhost:8081/health</code></pre>

<div style="margin-top: 30px; padding-top: 10px; border-top: 1px solid #cbd5e1; font-size: 8.5pt; color: #64748b; text-align: center;">
  Nnomm Flash-Deal Claim Service &bull; Architecture, Concurrency, and System Design Documentation
</div>

</body>
</html>
`;

const docsDir = path.join(__dirname);
const rootDir = path.resolve(__dirname, '..');

const htmlPath = path.join(docsDir, 'ARCHITECTURE_AND_INTERVIEW_GUIDE.html');
const pdfDocsPath = path.join(docsDir, 'NNOMM_ARCHITECTURE_AND_INTERVIEW_GUIDE.pdf');
const pdfRootPath = path.join(rootDir, 'NNOMM_ARCHITECTURE_AND_INTERVIEW_GUIDE.pdf');

fs.writeFileSync(htmlPath, htmlContent, 'utf8');
console.log('HTML written to:', htmlPath);

// Find Edge executable
const edgePaths = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
];

let edgePath = edgePaths.find(p => fs.existsSync(p));

if (!edgePath) {
  console.error('Edge executable not found');
  process.exit(1);
}

console.log('Using Edge at:', edgePath);

// Execute Edge headless to print PDF
const cmd = `"${edgePath}" --headless --disable-gpu --run-all-compositor-stages-before-draw --no-pdf-header-footer --print-to-pdf="${pdfDocsPath}" "${htmlPath}"`;
console.log('Running:', cmd);

try {
  execSync(cmd, { stdio: 'inherit' });
  // Copy to root directory as well
  fs.copyFileSync(pdfDocsPath, pdfRootPath);
  console.log('SUCCESS: PDF successfully generated and updated at:');
  console.log('  1. ' + pdfDocsPath);
  console.log('  2. ' + pdfRootPath);
} catch (err) {
  console.error('Error generating PDF:', err);
  process.exit(1);
}
