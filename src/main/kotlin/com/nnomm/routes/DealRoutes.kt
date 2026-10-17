package com.nnomm.routes

import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.ValidationException
import com.nnomm.security.JwtService
import com.nnomm.service.DealService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

fun Route.dealRoutes(dealService: DealService, jwtService: JwtService, json: Json) {
    route("/v1/deals") {
        post {
            val principal = call.extractAuthPrincipal(jwtService)
            val bytes = call.receiveBoundedByteArray()
            val request = try {
                json.decodeFromString<CreateDealRequest>(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw ValidationException("Malformed deal request body: ${e.message}")
            }

            val deal = dealService.createDeal(principal, request)
            val jsonResponse = json.encodeToString(deal)

            call.respondText(
                text = jsonResponse,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Created
            )
        }

        get("/{dealId}") {
            // "Any signed-in user"
            call.extractAuthPrincipal(jwtService)
            val dealIdStr = call.parameters["dealId"]
            val dealId = try {
                UUID.fromString(dealIdStr)
            } catch (e: Exception) {
                throw NotFoundException("Invalid deal ID")
            }

            val deal = dealService.getDeal(dealId)
            val jsonResponse = json.encodeToString(deal)

            call.respondText(
                text = jsonResponse,
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }
    }
}
