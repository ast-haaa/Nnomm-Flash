package com.nnomm.controller

import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.UserRole
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.HmacService
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimService
import com.nnomm.service.PaymentWebhookService
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@RestController
class DashboardController(
    private val jwtService: JwtService,
    private val hmacService: HmacService,
    private val claimService: ClaimService,
    private val paymentWebhookService: PaymentWebhookService
) {

    private val dashboardHtml: String by lazy {
        val resource = ClassPathResource("dashboard.html")
        resource.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    @GetMapping(value = ["/"], produces = [MediaType.TEXT_HTML_VALUE])
    fun index(): ResponseEntity<String> {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
            .body(dashboardHtml)
    }

    @GetMapping("/health", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }

    @GetMapping("/dev/tokens", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun devTokens(): ResponseEntity<Map<String, String>> {
        val adminSub = UUID.randomUUID()
        val restaurantId = UUID.randomUUID()
        val customerSub = UUID.randomUUID()

        val adminToken = jwtService.mintToken(adminSub, UserRole.restaurant_admin, restaurantId)
        val customerToken = jwtService.mintToken(customerSub, UserRole.customer, null)

        return ResponseEntity.ok(
            mapOf(
                "admin_token" to adminToken,
                "admin_sub" to adminSub.toString(),
                "restaurant_id" to restaurantId.toString(),
                "customer_token" to customerToken,
                "customer_sub" to customerSub.toString()
            )
        )
    }

    @GetMapping("/dev/blast-claim", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun blastClaim(
        @RequestParam("deal_id") dealIdStr: String,
        @RequestParam(value = "req_index", defaultValue = "1") reqIndex: String
    ): ResponseEntity<String> {
        val dealId = try {
            UUID.fromString(dealIdStr)
        } catch (e: Exception) {
            return ResponseEntity.badRequest().body("""{"error":"Invalid deal_id"}""")
        }

        val blastCustomer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
        val key = "blast-key-$reqIndex-${UUID.randomUUID()}"

        return try {
            val claimResult = claimService.claimDeal(blastCustomer, dealId, key, ClaimRequest(1))
            ResponseEntity.status(claimResult.statusCode).body(claimResult.responseBody)
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body("""{"status":409,"error":"${e.message ?: "Conflict"}"}""")
        }
    }

    @PostMapping("/dev/simulate-webhook", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun simulateWebhook(
        @RequestParam("claim_id") claimIdStr: String,
        @RequestParam(value = "event_type", defaultValue = "payment.succeeded") eventType: String
    ): ResponseEntity<Map<String, String>> {
        val claimId = try {
            UUID.fromString(claimIdStr)
        } catch (e: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid claim_id"))
        }

        val eventId = "sim-event-${UUID.randomUUID()}"
        val rawBodyStr = """{"event_id":"$eventId","type":"$eventType","claim_id":"$claimId","amount_paise":25000,"occurred_at":"${Instant.now()}"}"""
        val rawBytes = rawBodyStr.toByteArray(Charsets.UTF_8)
        val signature = hmacService.computeSignature(Instant.now().epochSecond, rawBytes)

        return try {
            val responseMsg = paymentWebhookService.processWebhook(signature, rawBytes)
            ResponseEntity.ok(
                mapOf(
                    "event_id" to eventId,
                    "event_type" to eventType,
                    "status" to "SUCCESS",
                    "message" to responseMsg
                )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Webhook processing failed")))
        }
    }
}
