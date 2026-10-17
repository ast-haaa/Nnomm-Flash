package com.nnomm.service

import com.nnomm.database.DatabaseFactory
import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.Deal
import com.nnomm.domain.ForbiddenException
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.UserRole
import com.nnomm.domain.ValidationException
import com.nnomm.repository.DealRepository
import com.nnomm.security.AuthPrincipal
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DealService(
    private val db: DatabaseFactory,
    private val dealRepository: DealRepository = DealRepository()
) {

    fun createDeal(principal: AuthPrincipal, request: CreateDealRequest): Deal {
        if (principal.role != UserRole.restaurant_admin) {
            throw ForbiddenException("Only restaurant admins can create deals")
        }
        val restaurantId = principal.restaurantId
            ?: throw ForbiddenException("Missing restaurant_id for admin")

        validateDealRequest(request)

        val deal = Deal(
            id = UUID.randomUUID(),
            restaurant_id = restaurantId,
            title = request.title.trim(),
            price_paise = request.price_paise,
            stock_total = request.stock_total,
            stock_available = request.stock_total,
            starts_at = request.starts_at,
            ends_at = request.ends_at
        )

        return db.withTransaction { conn ->
            dealRepository.create(conn, deal)
        }
    }

    fun getDeal(dealId: UUID): Deal {
        return db.withConnection { conn ->
            dealRepository.findById(conn, dealId)
        } ?: throw NotFoundException("Deal not found")
    }

    private fun validateDealRequest(req: CreateDealRequest) {
        if (req.title.isBlank() || req.title.length > 100) {
            throw ValidationException("Title must be between 1 and 100 characters")
        }
        if (req.price_paise < 1 || req.price_paise > 1000000) {
            throw ValidationException("price_paise must be between 1 and 1,000,000")
        }
        if (req.stock_total < 1 || req.stock_total > 10000) {
            throw ValidationException("stock_total must be between 1 and 10,000")
        }
        if (!req.starts_at.isBefore(req.ends_at)) {
            throw ValidationException("starts_at must be before ends_at")
        }
    }
}
