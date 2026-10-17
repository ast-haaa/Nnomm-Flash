package com.nnomm.security

import com.nnomm.domain.UnauthenticatedException
import jakarta.servlet.http.HttpServletRequest

fun HttpServletRequest.extractAuthPrincipal(jwtService: JwtService): AuthPrincipal {
    val authHeader = getHeader("Authorization")
        ?: throw UnauthenticatedException("Missing Authorization header")
    if (!authHeader.startsWith("Bearer ")) {
        throw UnauthenticatedException("Malformed Authorization header, must start with Bearer ")
    }
    val token = authHeader.removePrefix("Bearer ").trim()
    return jwtService.verifyToken(token)
}
