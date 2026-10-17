package com.nnomm

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.ForbiddenException
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.UnauthenticatedException
import com.nnomm.domain.UserRole
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthAndVisibilityTest {

    private val db = TestDatabaseHelper.dbFactory
    private val config = TestDatabaseHelper.config
    private val dealRepository = DealRepository()
    private val claimRepository = ClaimRepository()
    private val jwtService = JwtService(config.jwtSecret)
    private val dealService = DealService(db, dealRepository)
    private val claimService = ClaimService(config, db, dealRepository, claimRepository)
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        TestDatabaseHelper.cleanTables()
    }

    /**
     * Requirement 6: Customers can't read other customers' claims,
     * and admins can't read claims on other restaurants' deals.
     * Visibility rule returns 404, not 403!
     */
    @Test
    fun testClaimVisibilityRestrictions() {
        val restaurantIdA = UUID.randomUUID()
        val restaurantIdB = UUID.randomUUID()

        val adminA = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, restaurantIdA)
        val adminB = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, restaurantIdB)

        // Deal created for restaurant A
        val deal = dealService.createDeal(
            adminA,
            CreateDealRequest("Burger", 3000, 20, Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600))
        )

        val customerA = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
        val customerB = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)

        // Customer A claims
        val claimResult = claimService.claimDeal(customerA, deal.id, "claim-key-vis", ClaimRequest(quantity = 1))
        val claim = json.decodeFromString<com.nnomm.domain.Claim>(claimResult.responseBody)

        // 1. Customer A can read their own claim -> 200
        val fetchedByCustomerA = claimService.getClaim(customerA, claim.id)
        assertNotNull(fetchedByCustomerA)
        assertEquals(claim.id, fetchedByCustomerA.id)

        // 2. Customer B CANNOT read Customer A's claim -> throws 404 NOT_FOUND (not 403!)
        val exCustomerB = assertThrows<NotFoundException> {
            claimService.getClaim(customerB, claim.id)
        }
        assertEquals("NOT_FOUND", exCustomerB.code)

        // 3. Admin A (restaurant A) can read claim on their own deal -> 200
        val fetchedByAdminA = claimService.getClaim(adminA, claim.id)
        assertNotNull(fetchedByAdminA)
        assertEquals(claim.id, fetchedByAdminA.id)

        // 4. Admin B (restaurant B) CANNOT read claim on Restaurant A's deal -> throws 404 NOT_FOUND (not 403!)
        val exAdminB = assertThrows<NotFoundException> {
            claimService.getClaim(adminB, claim.id)
        }
        assertEquals("NOT_FOUND", exAdminB.code)
    }

    @Test
    fun testRoleRestrictionsOnEndpoints() {
        val customer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
        val admin = AuthPrincipal(UUID.randomUUID(), UserRole.restaurant_admin, UUID.randomUUID())

        // Customer trying to create a deal -> 403 FORBIDDEN
        assertThrows<ForbiddenException> {
            dealService.createDeal(
                customer,
                CreateDealRequest("Sushi", 4000, 10, Instant.now(), Instant.now().plusSeconds(3600))
            )
        }

        val deal = dealService.createDeal(
            admin,
            CreateDealRequest("Sushi", 4000, 10, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600))
        )

        // Admin trying to claim a deal -> 403 FORBIDDEN
        assertThrows<ForbiddenException> {
            claimService.claimDeal(admin, deal.id, "key-admin-claim", ClaimRequest(1))
        }
    }

    @Test
    fun testJwtTokenSecurity() {
        val customerId = UUID.randomUUID()

        // Valid token
        val validToken = jwtService.mintToken(customerId, UserRole.customer)
        val principal = jwtService.verifyToken(validToken)
        assertEquals(customerId, principal.sub)

        // Tampered token
        val tamperedToken = validToken.substring(0, validToken.length - 4) + "abcd"
        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(tamperedToken)
        }

        // Expired token
        val expiredToken = jwtService.mintToken(
            customerId,
            UserRole.customer,
            expiresAt = Date(System.currentTimeMillis() - 1000)
        )
        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(expiredToken)
        }

        // None algorithm token
        val noneToken = JWT.create()
            .withClaim("sub", customerId.toString())
            .withClaim("role", "customer")
            .sign(Algorithm.none())
        assertThrows<UnauthenticatedException> {
            jwtService.verifyToken(noneToken)
        }
    }
}
