package com.nnomm.plugins

import com.nnomm.routes.claimRoutes
import com.nnomm.routes.dealRoutes
import com.nnomm.routes.webhookRoutes
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import com.nnomm.service.PaymentWebhookService
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.configureRouting(
    dealService: DealService,
    claimService: ClaimService,
    paymentWebhookService: PaymentWebhookService,
    jwtService: JwtService,
    json: Json
) {
    routing {
        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }

        dealRoutes(dealService, jwtService, json)
        claimRoutes(claimService, jwtService, json)
        webhookRoutes(paymentWebhookService)
    }
}
