package com.nnomm

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.UserRole
import com.nnomm.security.HmacService
import com.nnomm.security.JwtService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class SpringMvcWebTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var hmacService: HmacService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    companion object {
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun configureProperties(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { TestDatabaseHelper.config.databaseUrl }
            registry.add("spring.datasource.username") { TestDatabaseHelper.config.databaseUser }
            registry.add("spring.datasource.password") { TestDatabaseHelper.config.databasePass }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }

    @BeforeEach
    fun setup() {
        TestDatabaseHelper.cleanTables()
    }

    @Test
    fun testHealthEndpoint() {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun testDashboardEndpoint() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "text/html; charset=UTF-8"))
    }

    @Test
    fun testDevTokensEndpoint() {
        mockMvc.perform(get("/dev/tokens"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.admin_token").isNotEmpty)
            .andExpect(jsonPath("$.customer_token").isNotEmpty)
    }

    @Test
    fun testDealCreationAndClaimingWorkflow() {
        val adminSub = UUID.randomUUID()
        val restaurantId = UUID.randomUUID()
        val adminToken = jwtService.mintToken(adminSub, UserRole.restaurant_admin, restaurantId)

        val dealReq = CreateDealRequest(
            title = "Special Pasta",
            price_paise = 45000,
            stock_total = 20,
            starts_at = Instant.now().minusSeconds(10),
            ends_at = Instant.now().plusSeconds(3600)
        )

        // 1. Create deal via POST /v1/deals
        val dealResult = mockMvc.perform(
            post("/v1/deals")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dealReq))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.title").value("Special Pasta"))
            .andExpect(jsonPath("$.stock_available").value(20))
            .andReturn()

        val dealJson = objectMapper.readTree(dealResult.response.contentAsString)
        val dealId = dealJson.get("id").asText()

        // 2. Fetch deal via GET /v1/deals/{dealId}
        mockMvc.perform(get("/v1/deals/$dealId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Special Pasta"))

        // 3. Customer claims deal via POST /v1/deals/{dealId}/claims
        val customerSub = UUID.randomUUID()
        val customerToken = jwtService.mintToken(customerSub, UserRole.customer, null)
        val idempotencyKey = "mvc-key-1"

        val claimReq = ClaimRequest(quantity = 2)

        val claimResult = mockMvc.perform(
            post("/v1/deals/$dealId/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(claimReq))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andReturn()

        val claimJson = objectMapper.readTree(claimResult.response.contentAsString)
        val claimId = claimJson.get("id").asText()

        // 4. Replay with exact same idempotency key -> Returns same claim, 201 Created or replayed
        mockMvc.perform(
            post("/v1/deals/$dealId/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(claimReq))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(claimId))
            .andExpect(header().string("X-Idempotent-Replay", "true"))

        // 5. Fetch claim via GET /v1/claims/{claimId}
        mockMvc.perform(
            get("/v1/claims/$claimId")
                .header("Authorization", "Bearer $customerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(claimId))
            .andExpect(jsonPath("$.status").value("RESERVED"))
    }

    @Test
    fun testPayloadTooLargeRejected() {
        val largeString = "a".repeat(17 * 1024)
        mockMvc.perform(
            post("/v1/deals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(largeString)
        )
            .andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun testPaymentWebhookController() {
        val adminSub = UUID.randomUUID()
        val restaurantId = UUID.randomUUID()
        val adminToken = jwtService.mintToken(adminSub, UserRole.restaurant_admin, restaurantId)

        val dealReq = CreateDealRequest(
            title = "Webhook Pizza",
            price_paise = 30000,
            stock_total = 10,
            starts_at = Instant.now().minusSeconds(10),
            ends_at = Instant.now().plusSeconds(3600)
        )

        val dealResult = mockMvc.perform(
            post("/v1/deals")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dealReq))
        ).andReturn()

        val dealId = objectMapper.readTree(dealResult.response.contentAsString).get("id").asText()

        val customerToken = jwtService.mintToken(UUID.randomUUID(), UserRole.customer, null)
        val claimResult = mockMvc.perform(
            post("/v1/deals/$dealId/claims")
                .header("Authorization", "Bearer $customerToken")
                .header("Idempotency-Key", "webhook-test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(1)))
        ).andReturn()

        val claimId = objectMapper.readTree(claimResult.response.contentAsString).get("id").asText()

        val eventId = "evt-spring-mvc-${UUID.randomUUID()}"
        val webhookPayload = """{"event_id":"$eventId","type":"payment.succeeded","claim_id":"$claimId","amount_paise":30000,"occurred_at":"${Instant.now()}"}"""
        val rawBytes = webhookPayload.toByteArray(Charsets.UTF_8)
        val timestamp = Instant.now().epochSecond
        val signature = hmacService.computeSignature(timestamp, rawBytes)

        mockMvc.perform(
            post("/v1/webhooks/payments")
                .header("X-Payment-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBytes)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ok"))

        // Verify status moved to PAID
        mockMvc.perform(
            get("/v1/claims/$claimId")
                .header("Authorization", "Bearer $customerToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAID"))
    }
}
