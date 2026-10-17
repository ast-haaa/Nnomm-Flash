package com.nnomm.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

object InstantSerializer : KSerializer<Instant> {
    private val formatter = DateTimeFormatter.ISO_INSTANT

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(formatter.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

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

@Serializable
data class Deal(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    @Serializable(with = UuidSerializer::class) val restaurant_id: UUID,
    val title: String,
    val price_paise: Long,
    val stock_total: Int,
    val stock_available: Int,
    @Serializable(with = InstantSerializer::class) val starts_at: Instant,
    @Serializable(with = InstantSerializer::class) val ends_at: Instant
)

@Serializable
data class CreateDealRequest(
    val title: String,
    val price_paise: Long,
    val stock_total: Int,
    @Serializable(with = InstantSerializer::class) val starts_at: Instant,
    @Serializable(with = InstantSerializer::class) val ends_at: Instant
)

@Serializable
data class Claim(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    @Serializable(with = UuidSerializer::class) val deal_id: UUID,
    @Serializable(with = UuidSerializer::class) val customer_id: UUID,
    val quantity: Int,
    val amount_paise: Long,
    val status: ClaimStatus,
    @Serializable(with = InstantSerializer::class) val expires_at: Instant
)

@Serializable
data class ClaimRequest(
    val quantity: Int
)

@Serializable
data class WebhookPayload(
    val event_id: String,
    val type: String,
    @Serializable(with = UuidSerializer::class) val claim_id: UUID,
    val amount_paise: Long,
    @Serializable(with = InstantSerializer::class) val occurred_at: Instant
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)
