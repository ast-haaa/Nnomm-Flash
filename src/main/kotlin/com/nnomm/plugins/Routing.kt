package com.nnomm.plugins

import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.UserRole
import com.nnomm.routes.claimRoutes
import com.nnomm.routes.dealRoutes
import com.nnomm.routes.webhookRoutes
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.HmacService
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import com.nnomm.service.PaymentWebhookService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

fun Application.configureRouting(
    dealService: DealService,
    claimService: ClaimService,
    paymentWebhookService: PaymentWebhookService,
    jwtService: JwtService,
    hmacService: HmacService,
    json: Json
) {
    routing {
        get("/") {
            call.respondText(DASHBOARD_HTML, ContentType.Text.Html)
        }

        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }

        // Dev helpers for interactive testing
        get("/dev/tokens") {
            val adminSub = UUID.randomUUID()
            val restaurantId = UUID.randomUUID()
            val customerSub = UUID.randomUUID()

            val adminToken = jwtService.mintToken(adminSub, UserRole.restaurant_admin, restaurantId)
            val customerToken = jwtService.mintToken(customerSub, UserRole.customer, null)

            val resp = buildJsonObject {
                put("admin_token", adminToken)
                put("admin_sub", adminSub.toString())
                put("restaurant_id", restaurantId.toString())
                put("customer_token", customerToken)
                put("customer_sub", customerSub.toString())
            }
            call.respondText(resp.toString(), ContentType.Application.Json)
        }

        get("/dev/blast-claim") {
            val dealIdStr = call.request.queryParameters["deal_id"]
            val reqIndex = call.request.queryParameters["req_index"] ?: "1"
            if (dealIdStr == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "deal_id required"))
                return@get
            }
            val dealId = try { UUID.fromString(dealIdStr) } catch(e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid deal_id"))
                return@get
            }
            val blastCustomer = AuthPrincipal(UUID.randomUUID(), UserRole.customer, null)
            val key = "blast-key-" + reqIndex + "-" + UUID.randomUUID()

            try {
                val claimResult = claimService.claimDeal(blastCustomer, dealId, key, ClaimRequest(1))
                call.respondText(claimResult.responseBody, ContentType.Application.Json, claimResult.statusCode)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Conflict, mapOf("status" to 409, "error" to (e.message ?: "Conflict")))
            }
        }

        post("/dev/simulate-webhook") {
            val claimIdStr = call.request.queryParameters["claim_id"]
            val eventType = call.request.queryParameters["event_type"] ?: "payment.succeeded"
            if (claimIdStr == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "claim_id required"))
                return@post
            }
            val claimId = try { UUID.fromString(claimIdStr) } catch(e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid claim_id"))
                return@post
            }

            val eventId = "sim-event-" + UUID.randomUUID()
            val rawBodyStr = """{"event_id":"$eventId","type":"$eventType","claim_id":"$claimId","amount_paise":25000}"""
            val rawBytes = rawBodyStr.toByteArray(Charsets.UTF_8)
            val signature = hmacService.computeSignature(Instant.now().epochSecond, rawBytes)

            try {
                val responseMsg = paymentWebhookService.processWebhook(signature, rawBytes)
                val resp = buildJsonObject {
                    put("event_id", eventId)
                    put("event_type", eventType)
                    put("status", "SUCCESS")
                    put("message", responseMsg)
                }
                call.respondText(resp.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Webhook processing failed")))
            }
        }

        dealRoutes(dealService, jwtService, json)
        claimRoutes(claimService, jwtService, json)
        webhookRoutes(paymentWebhookService)
    }
}
