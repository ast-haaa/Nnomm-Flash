package com.nnomm.service

import com.nnomm.config.AppConfig
import com.nnomm.database.DatabaseFactory
import com.nnomm.domain.AlreadyClaimedException
import com.nnomm.domain.AppException
import com.nnomm.domain.Claim
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.ClaimStatus
import com.nnomm.domain.DealNotLiveException
import com.nnomm.domain.ErrorDetail
import com.nnomm.domain.ErrorResponse
import com.nnomm.domain.ForbiddenException
import com.nnomm.domain.IdempotencyKeyInvalidException
import com.nnomm.domain.IdempotencyKeyReusedException
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.RequestInProgressException
import com.nnomm.domain.SoldOutException
import com.nnomm.domain.UserRole
import com.nnomm.domain.ValidationException
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import com.nnomm.repository.IdempotencyRepository
import com.nnomm.security.AuthPrincipal
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PSQLException
import java.time.Instant
import java.util.UUID

data class ClaimExecutionResult(
    val statusCode: HttpStatusCode,
    val responseBody: String,
    val isReplayed: Boolean
)

class ClaimService(
    private val config: AppConfig,
    private val db: DatabaseFactory,
    private val dealRepository: DealRepository = DealRepository(),
    private val claimRepository: ClaimRepository = ClaimRepository(),
    private val idempotencyRepository: IdempotencyRepository = IdempotencyRepository(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private val idempotencyKeyRegex = Regex("^[A-Za-z0-9-_]{1,64}$")

    fun claimDeal(
        principal: AuthPrincipal,
        dealId: UUID,
        idempotencyKey: String?,
        request: ClaimRequest
    ): ClaimExecutionResult {
        // 1. Role validation
        if (principal.role != UserRole.customer) {
            throw ForbiddenException("Only customers can claim deals")
        }

        // 2. Idempotency Key validation
        if (idempotencyKey == null || !idempotencyKeyRegex.matches(idempotencyKey)) {
            throw IdempotencyKeyInvalidException()
        }

        // 3. Input limits validation
        if (request.quantity !in 1..3) {
            throw ValidationException("quantity must be between 1 and 3")
        }

        val customerId = principal.sub
        val now = Instant.now()
        val lockTimeout = now.plusSeconds(60)

        // 4. Acquire / Inspect Idempotency Record
        val record = db.withTransaction { conn ->
            val existing = idempotencyRepository.findForUpdate(conn, customerId, idempotencyKey)
            if (existing != null) {
                // Different deal or quantity -> 422
                if (existing.dealId != dealId || existing.quantity != request.quantity) {
                    throw IdempotencyKeyReusedException()
                }

                // If already completed (201 or 409 conflict stored) -> replay
                if (existing.statusCode != null && existing.responseBody != null) {
                    return@withTransaction existing
                }

                // If still running and lock not expired -> 409 REQUEST_IN_PROGRESS
                if (existing.lockedUntil.isAfter(now)) {
                    throw RequestInProgressException()
                }

                // Previous process crashed mid-request! Lock expired (>60s) -> renew lock
                idempotencyRepository.renewLock(conn, customerId, idempotencyKey, dealId, request.quantity, lockTimeout)
                null
            } else {
                // Insert new in-progress lock
                idempotencyRepository.insertInProgress(conn, customerId, idempotencyKey, dealId, request.quantity, lockTimeout)
                null
            }
        }

        // If existing record was completed, replay it immediately
        if (record != null && record.statusCode != null && record.responseBody != null) {
            return ClaimExecutionResult(
                statusCode = HttpStatusCode.fromValue(record.statusCode),
                responseBody = record.responseBody,
                isReplayed = true
            )
        }

        // 5. Execute Claim Logic
        try {
            val result = executeClaimTransaction(customerId, dealId, request.quantity)
            val jsonResponse = json.encodeToString(result)

            // Save successful outcome (201) in idempotency table
            db.withTransaction { conn ->
                idempotencyRepository.saveResult(
                    conn,
                    customerId,
                    idempotencyKey,
                    HttpStatusCode.Created.value,
                    jsonResponse
                )
            }

            return ClaimExecutionResult(
                statusCode = HttpStatusCode.Created,
                responseBody = jsonResponse,
                isReplayed = false
            )
        } catch (e: AppException) {
            // Handle conflicts that must be stored for idempotency: SOLD_OUT, ALREADY_CLAIMED, DEAL_NOT_LIVE
            if (e is SoldOutException || e is AlreadyClaimedException || e is DealNotLiveException) {
                val errorResponse = ErrorResponse(ErrorDetail(code = e.code, message = e.message))
                val jsonResponse = json.encodeToString(errorResponse)
                try {
                    db.withTransaction { conn ->
                        idempotencyRepository.saveResult(
                            conn,
                            customerId,
                            idempotencyKey,
                            e.statusCode.value,
                            jsonResponse
                        )
                    }
                } catch (_: Exception) {}
            } else {
                // Other client errors (400, 401, 403, 404) aren't stored
                try {
                    db.withTransaction { conn ->
                        idempotencyRepository.delete(conn, customerId, idempotencyKey)
                    }
                } catch (_: Exception) {}
            }
            throw e
        } catch (e: Exception) {
            // Unexpected crash / failure: leave lock with lockedUntil to expire after 60s
            throw e
        }
    }

    private fun executeClaimTransaction(customerId: UUID, dealId: UUID, quantity: Int): Claim {
        return db.withTransaction { conn ->
            val now = Instant.now()

            // Lock deal row
            val deal = dealRepository.findByIdForUpdate(conn, dealId)
                ?: throw NotFoundException("Deal not found")

            // Check deal is live: starts_at up to, but not including, ends_at
            if (now.isBefore(deal.starts_at) || !now.isBefore(deal.ends_at)) {
                throw DealNotLiveException("Deal is not active at this time")
            }

            // Check if customer already holds an active claim
            if (claimRepository.hasActiveClaim(conn, customerId, dealId)) {
                throw AlreadyClaimedException()
            }

            // Check stock available
            if (deal.stock_available < quantity) {
                throw SoldOutException()
            }

            // Decrement stock
            val updated = dealRepository.decrementStock(conn, dealId, quantity)
            if (!updated) {
                throw SoldOutException()
            }

            val claimId = UUID.randomUUID()
            val expiresAt = now.plusSeconds(config.claimTtlSeconds)
            val amountPaise = deal.price_paise * quantity

            val claim = Claim(
                id = claimId,
                deal_id = dealId,
                customer_id = customerId,
                quantity = quantity,
                amount_paise = amountPaise,
                status = ClaimStatus.RESERVED,
                expires_at = expiresAt
            )

            try {
                claimRepository.create(conn, claim)
            } catch (ex: PSQLException) {
                // Check if unique index uq_active_customer_claim was violated concurrently
                if (ex.sqlState == "23505") {
                    throw AlreadyClaimedException()
                }
                throw ex
            }

            claim
        }
    }

    fun getClaim(principal: AuthPrincipal, claimId: UUID): Claim {
        val (claim, deal) = db.withConnection { conn ->
            val c = claimRepository.findById(conn, claimId)
            val d = if (c != null) dealRepository.findById(conn, c.deal_id) else null
            c to d
        }

        if (claim == null || deal == null) {
            throw NotFoundException("Claim not found")
        }

        // Visibility rule: A claim is visible only to its customer and to the admin of the deal's restaurant.
        // Anyone else gets 404, not 403!
        val isCustomer = principal.role == UserRole.customer && principal.sub == claim.customer_id
        val isAdminOfRestaurant = principal.role == UserRole.restaurant_admin && principal.restaurantId == deal.restaurant_id

        if (!isCustomer && !isAdminOfRestaurant) {
            throw NotFoundException("Claim not found")
        }

        // Expiry rule: "From expires_at on, the API never shows it as RESERVED"
        val now = Instant.now()
        return if (claim.status == ClaimStatus.RESERVED && !now.isBefore(claim.expires_at)) {
            claim.copy(status = ClaimStatus.EXPIRED)
        } else {
            claim
        }
    }
}
