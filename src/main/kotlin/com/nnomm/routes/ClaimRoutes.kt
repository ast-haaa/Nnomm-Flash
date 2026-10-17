package com.nnomm.routes

import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.ValidationException
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimService
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.claimRoutes(claimService: ClaimService, jwtService: JwtService, json: Json) {
    route("/v1") {
        post("/deals/{dealId}/claims") {
            val principal = call.extractAuthPrincipal(jwtService)
            val dealIdStr = call.parameters["dealId"]
            val dealId = try {
                UUID.fromString(dealIdStr)
            } catch (e: Exception) {
                throw NotFoundException("Invalid deal ID")
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]
            val bytes = call.receiveBoundedByteArray()
            val request = try {
                json.decodeFromString<ClaimRequest>(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw ValidationException("Malformed claim request body: ${e.message}")
            }

            val result = claimService.claimDeal(principal, dealId, idempotencyKey, request)

            if (result.isReplayed) {
                call.response.header("Idempotent-Replayed", "true")
            }

            call.respondText(
                text = result.responseBody,
                contentType = ContentType.Application.Json,
                status = result.statusCode
            )
        }

        get("/claims/{claimId}") {
            val principal = call.extractAuthPrincipal(jwtService)
            val claimIdStr = call.parameters["claimId"]
            val claimId = try {
                UUID.fromString(claimIdStr)
            } catch (e: Exception) {
                throw NotFoundException("Invalid claim ID")
            }

            val claim = claimService.getClaim(principal, claimId)
            val jsonResponse = json.encodeToString(claim)

            call.respondText(
                text = jsonResponse,
                contentType = ContentType.Application.Json
            )
        }
    }
}
