package com.nnomm.domain

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant
import java.util.UUID

enum class ClaimStatus {
    RESERVED,
    PAID,
    EXPIRED,
    NEEDS_REVIEW,
    REFUND_PENDING
}

enum class UserRole {
    customer,
    restaurant_admin
}

data class Deal(
    val id: UUID,
    val restaurant_id: UUID,
    val title: String,
    val price_paise: Long,
    val stock_total: Int,
    val stock_available: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val starts_at: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val ends_at: Instant
)

data class CreateDealRequest(
    val title: String,
    val price_paise: Long,
    val stock_total: Int,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val starts_at: Instant,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val ends_at: Instant
)

data class Claim(
    val id: UUID,
    val deal_id: UUID,
    val customer_id: UUID,
    val quantity: Int,
    val amount_paise: Long,
    val status: ClaimStatus,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expires_at: Instant
)

data class ClaimRequest(
    val quantity: Int
)

data class WebhookPayload(
    val event_id: String,
    val type: String,
    val claim_id: UUID,
    val amount_paise: Long,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val occurred_at: Instant
)

data class ErrorDetail(
    val code: String,
    val message: String
)

data class ErrorResponse(
    val error: ErrorDetail
)
