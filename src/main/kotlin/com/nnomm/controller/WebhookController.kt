package com.nnomm.controller

import com.nnomm.security.CachedBodyHttpServletRequest
import com.nnomm.service.PaymentWebhookService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/webhooks")
class WebhookController(
    private val paymentWebhookService: PaymentWebhookService
) {

    @PostMapping("/payments")
    fun handlePaymentWebhook(request: HttpServletRequest): ResponseEntity<Map<String, String>> {
        val signatureHeader = request.getHeader("X-Payment-Signature")
        val rawBody = if (request is CachedBodyHttpServletRequest) {
            request.cachedBody
        } else {
            request.inputStream.readBytes()
        }

        paymentWebhookService.processWebhook(signatureHeader, rawBody)
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }
}
