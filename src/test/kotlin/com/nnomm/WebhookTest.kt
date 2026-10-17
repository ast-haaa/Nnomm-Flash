package com.nnomm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.domain.Claim
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.UnauthenticatedException
import com.nnomm.domain.UserRole
import com.nnomm.domain.WebhookPayload
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.HmacService
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import com.nnomm.service.PaymentWebhookService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class WebhookTest {

    private val db = TestDatabaseHelper.dbFactory
    private val config = TestDatabaseHelper.config
    private val dealRepository = DealRepository()
    private val claimRepository = ClaimRepository()
    private val hmacService = HmacService(config.webhookSecret)
    private val dealService = DealService(db, dealRepository)
    private val claimService = ClaimService(config, db, dealRepository, claimRepository)
    private val webhookService = PaymentWebhookService(hmacService, db, claimRepository, dealRepository)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setup() {
        TestDatabaseHelper.cleanTables()
    }

    private fun setupDealAndClaim(quantity: Int = 1): Pair<UUID, UUID> {
        val adminPrincipal = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())
        val deal = dealService.createDeal(
            adminPrincipal,
            CreateDealRequest("Test Pizza", 5000, 10, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )
        val customerPrincipal = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
        val claimResult = claimService.claimDeal(customerPrincipal, deal.id, "key-1", ClaimRequest(quantity))
        val claim = objectMapper.readValue(claimResult.responseBody, Claim::class.java)
        return deal.id to claim.id
    }

    @Test
    fun testBadSignatureRejected() {
        val (_, claimId) = setupDealAndClaim()
        val payload = WebhookPayload("evt-1", "payment.succeeded", claimId, 5000, Instant.now())
        val rawBody = objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8)

        // Missing signature
        assertThrows<UnauthenticatedException> {
            webhookService.processWebhook(null, rawBody)
        }

        // Malformed signature
        assertThrows<UnauthenticatedException> {
            webhookService.processWebhook("invalid-signature", rawBody)
        }

        // Wrong secret / tampered
        val badHmacService = HmacService("wrong-secret-key-that-does-not-match")
        val badSignature = badHmacService.computeSignature(Instant.now().epochSecond, rawBody)
        assertThrows<UnauthenticatedException> {
            webhookService.processWebhook(badSignature, rawBody)
        }
    }

    @Test
    fun testStaleTimestampRejected() {
        val (_, claimId) = setupDealAndClaim()
        val payload = WebhookPayload("evt-2", "payment.succeeded", claimId, 5000, Instant.now())
        val rawBody = objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8)

        // 301 seconds in the past
        val staleTimestamp = Instant.now().epochSecond - 301
        val signaturePast = hmacService.computeSignature(staleTimestamp, rawBody)
        assertThrows<UnauthenticatedException> {
            webhookService.processWebhook(signaturePast, rawBody)
        }

        // 301 seconds in the future
        val futureTimestamp = Instant.now().epochSecond + 301
        val signatureFuture = hmacService.computeSignature(futureTimestamp, rawBody)
        assertThrows<UnauthenticatedException> {
            webhookService.processWebhook(signatureFuture, rawBody)
        }
    }

    @Test
    fun testDuplicateEventsAreIdempotent() {
        val (_, claimId) = setupDealAndClaim()
        val payload = WebhookPayload("evt-dup", "payment.succeeded", claimId, 5000, Instant.now())
        val rawBody = objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8)
        val signature = hmacService.computeSignature(Instant.now().epochSecond, rawBody)

        // First delivery: processes payment -> PAID
        val res1 = webhookService.processWebhook(signature, rawBody)
        assertEquals("OK", res1)

        val claimAfterFirst = db.withConnection { conn -> claimRepository.findById(conn, claimId) }!!
        assertEquals(ClaimStatus.PAID, claimAfterFirst.status)

        // Second delivery (duplicate event_id): should be recognized as duplicate, 200 no change
        val res2 = webhookService.processWebhook(signature, rawBody)
        assertEquals("OK", res2)

        val claimAfterSecond = db.withConnection { conn -> claimRepository.findById(conn, claimId) }!!
        assertEquals(ClaimStatus.PAID, claimAfterSecond.status)
    }

    @Test
    fun testPaymentAmountMismatchMovesToNeedsReview() {
        val (_, claimId) = setupDealAndClaim()
        // Expected amount: 5000, Paid amount: 4000
        val payload = WebhookPayload("evt-mismatch", "payment.succeeded", claimId, 4000, Instant.now())
        val rawBody = objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8)
        val signature = hmacService.computeSignature(Instant.now().epochSecond, rawBody)

        webhookService.processWebhook(signature, rawBody)

        val claim = db.withConnection { conn -> claimRepository.findById(conn, claimId) }!!
        assertEquals(ClaimStatus.NEEDS_REVIEW, claim.status)
    }

    @Test
    fun testPaymentFailedDoesNotChangeStatus() {
        val (_, claimId) = setupDealAndClaim()
        val payload = WebhookPayload("evt-fail", "payment.failed", claimId, 5000, Instant.now())
        val rawBody = objectMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8)
        val signature = hmacService.computeSignature(Instant.now().epochSecond, rawBody)

        webhookService.processWebhook(signature, rawBody)

        val claim = db.withConnection { conn -> claimRepository.findById(conn, claimId) }!!
        assertEquals(ClaimStatus.RESERVED, claim.status, "Customer may pay again until expiry")
    }
}
