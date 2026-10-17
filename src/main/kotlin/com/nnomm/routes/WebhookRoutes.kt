package com.nnomm.routes

import com.nnomm.service.PaymentWebhookService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.webhookRoutes(paymentWebhookService: PaymentWebhookService) {
    route("/v1/webhooks/payments") {
        post {
            val signature = call.request.headers["X-Payment-Signature"]
            val rawBytes = call.receiveBoundedByteArray()

            paymentWebhookService.processWebhook(signature, rawBytes)

            call.respondText(
                text = """{"status":"ok"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }
    }
}
