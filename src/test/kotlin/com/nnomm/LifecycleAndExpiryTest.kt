package com.nnomm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nnomm.domain.Claim
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.DealNotLiveException
import com.nnomm.domain.IdempotencyKeyReusedException
import com.nnomm.domain.SoldOutException
import com.nnomm.domain.UserRole
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.security.AuthPrincipal
import com.nnomm.service.ClaimExpiryWorker
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifecycleAndExpiryTest {

    private val db = TestDatabaseHelper.dbFactory
    private val config = TestDatabaseHelper.config
    private val dealRepository = DealRepository()
    private val claimRepository = ClaimRepository()
    private val dealService = DealService(db, dealRepository)
    private val claimService = ClaimService(config, db, dealRepository, claimRepository)
    private val expiryWorker = ClaimExpiryWorker(db)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setup() {
        TestDatabaseHelper.cleanTables()
    }

    @Test
    fun testClaimExpiryReclaimsUnitsAndHidesReservedStatus() {
        val admin = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())
        val customer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)

        val deal = dealService.createDeal(
            admin,
            CreateDealRequest("Kebab", 2500, 5, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )

        val claimResult = claimService.claimDeal(customer, deal.id, "expiry-key-1", ClaimRequest(2))
        val claim = objectMapper.readValue(claimResult.responseBody, Claim::class.java)

        var currentDeal = dealService.getDeal(deal.id)
        assertEquals(3, currentDeal.stock_available, "Stock must decrement to 3")

        // Age claim past expires_at
        db.withConnection { conn ->
            val stmt = conn.prepareStatement("UPDATE claims SET expires_at = NOW() - INTERVAL '5 seconds' WHERE id = ?")
            stmt.setObject(1, claim.id)
            stmt.executeUpdate()
        }

        // Even before reaper runs: "From expires_at on, the API never shows it as RESERVED"
        val fetchedClaimBeforeReaper = claimService.getClaim(customer, claim.id)
        assertEquals(ClaimStatus.EXPIRED, fetchedClaimBeforeReaper.status)

        // Run expiry worker sweep
        val reclaimedUnits = expiryWorker.sweepExpiredClaims()
        assertEquals(2, reclaimedUnits, "Reaper must reclaim 2 units")

        currentDeal = dealService.getDeal(deal.id)
        assertEquals(5, currentDeal.stock_available, "Stock must return to 5")

        // After expiry, customer is allowed to claim again!
        val secondClaim = claimService.claimDeal(customer, deal.id, "expiry-key-2", ClaimRequest(1))
        assertEquals(201, secondClaim.statusCode)
    }

    @Test
    fun testDealNotLiveBeforeOrAfter() {
        val admin = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())
        val customer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)

        // Deal in future
        val futureDeal = dealService.createDeal(
            admin,
            CreateDealRequest("Future Deal", 1000, 5, Instant.now().plusSeconds(600), Instant.now().plusSeconds(1200))
        )
        assertThrows<DealNotLiveException> {
            claimService.claimDeal(customer, futureDeal.id, "future-key", ClaimRequest(1))
        }

        // Deal in past
        val pastDeal = dealService.createDeal(
            admin,
            CreateDealRequest("Past Deal", 1000, 5, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(600))
        )
        assertThrows<DealNotLiveException> {
            claimService.claimDeal(customer, pastDeal.id, "past-key", ClaimRequest(1))
        }
    }

    @Test
    fun testIdempotencyReusedWithDifferentParameters() {
        val admin = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())
        val customer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)

        val deal1 = dealService.createDeal(
            admin,
            CreateDealRequest("Deal 1", 1000, 5, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )
        val deal2 = dealService.createDeal(
            admin,
            CreateDealRequest("Deal 2", 1000, 5, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )

        val key = "key-idem-reuse"
        // First call
        claimService.claimDeal(customer, deal1.id, key, ClaimRequest(quantity = 1))

        // Same key, different quantity -> 422 IDEMPOTENCY_KEY_REUSED
        assertThrows<IdempotencyKeyReusedException> {
            claimService.claimDeal(customer, deal1.id, key, ClaimRequest(quantity = 2))
        }

        // Same key, different deal -> 422 IDEMPOTENCY_KEY_REUSED
        assertThrows<IdempotencyKeyReusedException> {
            claimService.claimDeal(customer, deal2.id, key, ClaimRequest(quantity = 1))
        }
    }

    @Test
    fun testReplayedConflictStaysConflict() {
        val admin = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())
        val customer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)

        // Deal with 0 initial stock (or 1 stock claimed by someone else)
        val deal = dealService.createDeal(
            admin,
            CreateDealRequest("Soldout Deal", 1000, 1, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )

        // Customer X claims the 1 unit
        val customerX = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
        claimService.claimDeal(customerX, deal.id, "key-cust-x", ClaimRequest(1))

        // Customer tries to claim -> fails with SOLD_OUT
        val key = "soldout-key"
        assertThrows<SoldOutException> {
            claimService.claimDeal(customer, deal.id, key, ClaimRequest(1))
        }

        // Even if 1 unit frees up later (e.g. claim expired/cancelled), replaying the same key stays SOLD_OUT!
        db.withConnection { conn -> dealRepository.incrementStock(conn, deal.id, 1) }

        // Replaying same key returns SOLD_OUT
        val replayed = claimService.claimDeal(customer, deal.id, key, ClaimRequest(1))
        assertTrue(replayed.isReplayed)
        assertEquals(409, replayed.statusCode)
        assertTrue(replayed.responseBody.contains("SOLD_OUT"))
    }
}
