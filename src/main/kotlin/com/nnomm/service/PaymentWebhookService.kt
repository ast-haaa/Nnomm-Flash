package com.nnomm.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nnomm.database.DatabaseFactory
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.ValidationException
import com.nnomm.domain.WebhookPayload
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.repository.PaymentEventRepository
import com.nnomm.security.HmacService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PaymentWebhookService(
    private val hmacService: HmacService,
    private val db: DatabaseFactory,
    private val claimRepository: ClaimRepository = ClaimRepository(),
    private val dealRepository: DealRepository = DealRepository(),
    private val paymentEventRepository: PaymentEventRepository = PaymentEventRepository(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
) {
    private val log = LoggerFactory.getLogger(PaymentWebhookService::class.java)

    fun processWebhook(signatureHeader: String?, rawBody: ByteArray): String {
        // 1. Verify HMAC signature and timestamp
        hmacService.verifySignature(signatureHeader, rawBody)

        // 2. Deserialize payload
        val payload = try {
            objectMapper.readValue<WebhookPayload>(rawBody)
        } catch (e: Exception) {
            throw ValidationException("Malformed webhook payload")
        }

        val now = Instant.now()

        // 3. Process event within a database transaction
        db.withTransaction { conn ->
            // Idempotency: Check if event_id already processed
            if (paymentEventRepository.isEventProcessed(conn, payload.event_id)) {
                log.info("Webhook event {} already processed, skipping with 200 OK", payload.event_id)
                return@withTransaction
            }

            // Lock claim row for update
            val claim = claimRepository.findByIdForUpdate(conn, payload.claim_id)
            if (claim == null) {
                // Unknown claim_id:
                // We reject with 404 NOT_FOUND to alert the payment gateway of an invalid resource ID.
                log.warn("Webhook received for unknown claim_id {}", payload.claim_id)
                throw NotFoundException("Claim not found for webhook event")
            }

            if (payload.type == "payment.failed") {
                // payment.failed: Any status -> 200, no change; customer may pay again until expiry
                paymentEventRepository.recordEvent(
                    conn,
                    payload.event_id,
                    claim.id,
                    payload.type,
                    payload.amount_paise,
                    payload.occurred_at,
                    "FAILED_NO_OP"
                )
                return@withTransaction
            }

            if (payload.type == "payment.succeeded") {
                when (claim.status) {
                    ClaimStatus.RESERVED -> {
                        if (now.isBefore(claim.expires_at)) {
                            // Payment arrived before expiry
                            if (payload.amount_paise == claim.amount_paise) {
                                claimRepository.updateStatus(conn, claim.id, ClaimStatus.PAID)
                                paymentEventRepository.recordEvent(
                                    conn,
                                    payload.event_id,
                                    claim.id,
                                    payload.type,
                                    payload.amount_paise,
                                    payload.occurred_at,
                                    "PAID"
                                )
                            } else {
                                // Amount differs: NEEDS_REVIEW; units stay held
                                claimRepository.updateStatus(conn, claim.id, ClaimStatus.NEEDS_REVIEW)
                                paymentEventRepository.recordEvent(
                                    conn,
                                    payload.event_id,
                                    claim.id,
                                    payload.type,
                                    payload.amount_paise,
                                    payload.occurred_at,
                                    "NEEDS_REVIEW"
                                )
                            }
                        } else {
                            // Payment succeeded but arrived after expires_at:
                            // Transition to REFUND_PENDING. Return units to stock since it was still marked RESERVED.
                            claimRepository.updateStatus(conn, claim.id, ClaimStatus.REFUND_PENDING)
                            dealRepository.incrementStock(conn, claim.deal_id, claim.quantity)
                            paymentEventRepository.recordEvent(
                                conn,
                                payload.event_id,
                                claim.id,
                                payload.type,
                                payload.amount_paise,
                                payload.occurred_at,
                                "REFUND_PENDING_LATE_FROM_RESERVED"
                            )
                        }
                    }

                    ClaimStatus.EXPIRED -> {
                        // Payment succeeded after claim was already marked EXPIRED by reaper:
                        // Units were already returned to stock upon expiry. Units stay in stock!
                        claimRepository.updateStatus(conn, claim.id, ClaimStatus.REFUND_PENDING)
                        paymentEventRepository.recordEvent(
                            conn,
                            payload.event_id,
                            claim.id,
                            payload.type,
                            payload.amount_paise,
                            payload.occurred_at,
                            "REFUND_PENDING_FROM_EXPIRED"
                        )
                    }

                    ClaimStatus.PAID, ClaimStatus.NEEDS_REVIEW, ClaimStatus.REFUND_PENDING -> {
                        // A new event_id for an already settled claim:
                        // Record as duplicate/excess payment; keep current status and units untouched.
                        paymentEventRepository.recordEvent(
                            conn,
                            payload.event_id,
                            claim.id,
                            payload.type,
                            payload.amount_paise,
                            payload.occurred_at,
                            "DUPLICATE_PAYMENT_IGNORED"
                        )
                    }
                }
            }
        }

        return "OK"
    }
}
