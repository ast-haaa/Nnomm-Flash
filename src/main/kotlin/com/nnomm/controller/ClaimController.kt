package com.nnomm.controller

import com.nnomm.domain.Claim
import com.nnomm.domain.ClaimRequest
import com.nnomm.domain.NotFoundException
import com.nnomm.domain.ValidationException
import com.nnomm.security.JwtService
import com.nnomm.security.extractAuthPrincipal
import com.nnomm.service.ClaimService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ClaimController(
    private val claimService: ClaimService,
    private val jwtService: JwtService
) {

    @PostMapping("/v1/deals/{dealId}/claims")
    fun claimDeal(
        request: HttpServletRequest,
        @PathVariable dealId: String,
        @RequestBody claimReq: ClaimRequest
    ): ResponseEntity<String> {
        val principal = request.extractAuthPrincipal(jwtService)
        val id = try {
            UUID.fromString(dealId)
        } catch (e: Exception) {
            throw ValidationException("Invalid deal ID format")
        }
        val idempotencyKey = request.getHeader("Idempotency-Key")

        val result = claimService.claimDeal(principal, id, idempotencyKey, claimReq)

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (result.isReplayed) {
            headers.set("Idempotent-Replayed", "true")
            headers.set("X-Idempotent-Replay", "true")
        }

        return ResponseEntity.status(result.statusCode).headers(headers).body(result.responseBody)
    }

    @GetMapping("/v1/claims/{claimId}")
    fun getClaim(
        request: HttpServletRequest,
        @PathVariable claimId: String
    ): ResponseEntity<Claim> {
        val principal = request.extractAuthPrincipal(jwtService)
        val id = try {
            UUID.fromString(claimId)
        } catch (e: Exception) {
            throw NotFoundException("Invalid claim ID format")
        }

        val claim = claimService.getClaim(principal, id)
        return ResponseEntity.ok(claim)
    }
}
