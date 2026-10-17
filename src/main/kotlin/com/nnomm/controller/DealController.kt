package com.nnomm.controller

import com.nnomm.domain.CreateDealRequest
import com.nnomm.domain.Deal
import com.nnomm.domain.ValidationException
import com.nnomm.security.JwtService
import com.nnomm.security.extractAuthPrincipal
import com.nnomm.service.DealService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/deals")
class DealController(
    private val dealService: DealService,
    private val jwtService: JwtService
) {

    @PostMapping
    fun createDeal(
        request: HttpServletRequest,
        @RequestBody createReq: CreateDealRequest
    ): ResponseEntity<Deal> {
        val principal = request.extractAuthPrincipal(jwtService)
        val deal = dealService.createDeal(principal, createReq)
        return ResponseEntity.status(HttpStatus.CREATED).body(deal)
    }

    @GetMapping("/{dealId}")
    fun getDeal(
        @PathVariable dealId: String
    ): ResponseEntity<Deal> {
        val id = try {
            UUID.fromString(dealId)
        } catch (e: Exception) {
            throw ValidationException("Invalid deal ID format")
        }
        val deal = dealService.getDeal(id)
        return ResponseEntity.ok(deal)
    }
}
