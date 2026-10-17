package com.nnomm

import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.Deal
import com.nnomm.domain.UserRole
import com.nnomm.domain.WebhookPayload
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.HmacService
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimExpiryWorker
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import com.nnomm.service.PaymentWebhookService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest {

    private val db = TestDatabaseHelper.dbFactory
    private val config = TestDatabaseHelper.config
    private val dealRepository = DealRepository()
    private val claimRepository = ClaimRepository()
    private val jwtService = JwtService(config.jwtSecret)
    private val hmacService = HmacService(config.webhookSecret)
    private val dealService = DealService(db, dealRepository)
    private val claimService = ClaimService(config, db, dealRepository, claimRepository)
    private val webhookService = PaymentWebhookService(hmacService, db, claimRepository, dealRepository)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setup() {
        TestDatabaseHelper.cleanTables()
    }

    private fun createLiveDeal(stockTotal: Int = 50, pricePaise: Long = 1000): Deal {
        val adminPrincipal = AuthPrincipal(
            sub = UUID.randomUUID(),
            role = UserRole.restaurant_admin,
            restaurantId = UUID.randomUUID()
        )
        return dealService.createDeal(
            principal = adminPrincipal,
            request = CreateDealRequest(
                title = "Biryani Feast",
                price_paise = pricePaise,
                stock_total = stockTotal,
                starts_at = Instant.now().minusSeconds(60),
                ends_at = Instant.now().plusSeconds(3600)
            )
        )
    }

    /**
     * Requirement 1: 200 or more concurrent claims never oversell a 50-unit deal.
     */
    @Test
    fun test200ConcurrentClaimsNeverOversell() = runBlocking(Dispatchers.IO) {
        val stockTotal = 50
        val deal = createLiveDeal(stockTotal = stockTotal)
        val numRequests = 220

        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val otherCount = AtomicInteger(0)

        val jobs = (1..numRequests).map {
            async {
                val customerPrincipal = AuthPrincipal(
                    sub = UUID.randomUUID(),
                    role = UserRole.customer,
                    restaurantId = null
                )
                val idempotencyKey = UUID.randomUUID().toString()
                try {
                    val result = claimService.claimDeal(
                        principal = customerPrincipal,
                        dealId = deal.id,
                        idempotencyKey = idempotencyKey,
                        request = ClaimRequest(quantity = 1)
                    )
                    if (result.statusCode == 201) {
                        successCount.incrementAndGet()
                    } else {
                        otherCount.incrementAndGet()
                    }
                } catch (e: com.nnomm.domain.SoldOutException) {
                    soldOutCount.incrementAndGet()
                } catch (e: Exception) {
                    otherCount.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        val updatedDeal = dealService.getDeal(deal.id)

        assertEquals(stockTotal, successCount.get(), "Exactly 50 claims must succeed")
        assertEquals(numRequests - stockTotal, soldOutCount.get(), "Remaining 170 requests must get SOLD_OUT")
        assertEquals(0, otherCount.get(), "No unexpected errors should occur")
        assertEquals(0, updatedDeal.stock_available, "Available stock must be 0")
        assertTrue(updatedDeal.stock_available >= 0, "Available stock must never be negative")
    }

    /**
     * Requirement 2: 20 or more concurrent requests with one Idempotency-Key create exactly one claim.
     */
    @Test
    fun testConcurrentRequestsWithSameIdempotencyKey() = runBlocking(Dispatchers.IO) {
        val deal = createLiveDeal(stockTotal = 50)
        val customerId = UUID.randomUUID()
        val customerPrincipal = AuthPrincipal(
            sub = customerId,
            role = UserRole.customer,
            restaurantId = null
        )
        val key = "idem-single-key-${UUID.randomUUID()}"
        val numRequests = 25

        val createdCount = AtomicInteger(0)
        val replayedCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        val jobs = (1..numRequests).map {
            async {
                try {
                    val result = claimService.claimDeal(
                        principal = customerPrincipal,
                        dealId = deal.id,
                        idempotencyKey = key,
                        request = ClaimRequest(quantity = 2)
                    )
                    if (result.statusCode == 201) {
                        if (result.isReplayed) {
                            replayedCount.incrementAndGet()
                        } else {
                            createdCount.incrementAndGet()
                        }
                    }
                } catch (e: com.nnomm.domain.RequestInProgressException) {
                    rejectedCount.incrementAndGet()
                } catch (e: com.nnomm.domain.AlreadyClaimedException) {
                    // Under extreme concurrency, a second coroutine may race past the
                    // idempotency lock and reach the unique index; this is safe because
                    // exactly one claim is still created.
                    rejectedCount.incrementAndGet()
                }
                Unit
            }
        }

        jobs.awaitAll()

        // Exactly one created outcome
        assertEquals(1, createdCount.get(), "Exactly one original claim must be created")

        // Inspect database: exactly 1 claim in database
        val claimCount = db.withConnection { conn ->
            val stmt = conn.prepareStatement("SELECT count(*) FROM claims WHERE customer_id = ?")
            stmt.setObject(1, customerId)
            val rs = stmt.executeQuery()
            rs.next()
            rs.getInt(1)
        }
        assertEquals(1, claimCount, "Database must contain exactly 1 claim for this customer")
    }

    /**
     * Requirement 3: Concurrent claims by one customer with different keys create exactly one claim.
     */
    @Test
    fun testConcurrentClaimsBySameCustomerWithDifferentKeys() = runBlocking(Dispatchers.IO) {
        val deal = createLiveDeal(stockTotal = 50)
        val customerId = UUID.randomUUID()
        val customerPrincipal = AuthPrincipal(
            sub = customerId,
            role = UserRole.customer,
            restaurantId = null
        )

        val successCount = AtomicInteger(0)
        val alreadyClaimedCount = AtomicInteger(0)
        val numRequests = 20

        val jobs = (1..numRequests).map {
            async {
                val key = UUID.randomUUID().toString()
                try {
                    val result = claimService.claimDeal(
                        principal = customerPrincipal,
                        dealId = deal.id,
                        idempotencyKey = key,
                        request = ClaimRequest(quantity = 1)
                    )
                    if (result.statusCode == 201) {
                        successCount.incrementAndGet()
                    }
                } catch (e: com.nnomm.domain.AlreadyClaimedException) {
                    alreadyClaimedCount.incrementAndGet()
                }
                Unit
            }
        }

        jobs.awaitAll()

        assertEquals(1, successCount.get(), "Customer can hold at most one active claim")
        assertEquals(numRequests - 1, alreadyClaimedCount.get(), "Other attempts must fail with ALREADY_CLAIMED")

        val dbClaimCount = db.withConnection { conn ->
            val stmt = conn.prepareStatement("SELECT count(*) FROM claims WHERE customer_id = ?")
            stmt.setObject(1, customerId)
            val rs = stmt.executeQuery()
            rs.next()
            rs.getInt(1)
        }
        assertEquals(1, dbClaimCount, "Database must have exactly 1 claim record")
    }

    /**
     * Requirement 5: A payment and expiry racing on the same claim leave the stock invariant intact.
     */
    @Test
    fun testPaymentAndExpiryRaceLeavesStockInvariantIntact() = runBlocking(Dispatchers.IO) {
        val stockTotal = 10
        val deal = createLiveDeal(stockTotal = stockTotal)
        val customerId = UUID.randomUUID()
        val customerPrincipal = AuthPrincipal(sub = customerId, role = UserRole.customer, restaurantId = null)

        // Claim 2 units
        val claimResult = claimService.claimDeal(
            principal = customerPrincipal,
            dealId = deal.id,
            idempotencyKey = "race-test-key",
            request = ClaimRequest(quantity = 2)
        )
        val claim = objectMapper.readValue(claimResult.responseBody, com.nnomm.domain.Claim::class.java)

        // Artificially age the claim so it is due for expiry
        db.withConnection { conn ->
            val stmt = conn.prepareStatement("UPDATE claims SET expires_at = NOW() - INTERVAL '1 second' WHERE id = ?")
            stmt.setObject(1, claim.id)
            stmt.executeUpdate()
        }

        val expiryWorker = ClaimExpiryWorker(db)

        val paymentPayload = WebhookPayload(
            event_id = "evt-race-${UUID.randomUUID()}",
            type = "payment.succeeded",
            claim_id = claim.id,
            amount_paise = claim.amount_paise,
            occurred_at = Instant.now()
        )
        val rawBody = objectMapper.writeValueAsString(paymentPayload).toByteArray(Charsets.UTF_8)
        val timestamp = Instant.now().epochSecond
        val signature = hmacService.computeSignature(timestamp, rawBody)

        // Race expiry worker and webhook concurrently
        val results = ConcurrentLinkedQueue<String>()
        val jobExpiry = async {
            val reclaimed = expiryWorker.sweepExpiredClaims()
            results.add("expiry: reclaimed $reclaimed")
        }
        val jobPayment = async {
            try {
                webhookService.processWebhook(signature, rawBody)
                results.add("payment: success")
            } catch (e: Exception) {
                results.add("payment: ${e.message}")
            }
        }

        jobExpiry.await()
        jobPayment.await()

        val finalClaim = db.withConnection { conn ->
            claimRepository.findById(conn, claim.id)
        }!!

        val finalDeal = dealService.getDeal(deal.id)

        // Status must be either PAID or REFUND_PENDING (never left in an inconsistent state)
        assertTrue(
            finalClaim.status == ClaimStatus.PAID || finalClaim.status == ClaimStatus.REFUND_PENDING,
            "Claim status must be PAID or REFUND_PENDING, was ${finalClaim.status}"
        )

        // Invariant: stock_available + active_units == stock_total
        val activeUnits = if (finalClaim.status == ClaimStatus.PAID) finalClaim.quantity else 0
        assertEquals(
            stockTotal,
            finalDeal.stock_available + activeUnits,
            "Invariant must hold: stock_available ($finalDeal.stock_available) + activeUnits ($activeUnits) == stockTotal ($stockTotal)"
        )
    }
}
