package com.nnomm.routes

import com.nnomm.domain.PayloadTooLargeException
import com.nnomm.domain.UnauthenticatedException
import com.nnomm.security.AuthPrincipal
import com.nnomm.security.JwtService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining

const val MAX_BODY_SIZE_BYTES = 16 * 1024 // 16 KB

fun ApplicationCall.extractAuthPrincipal(jwtService: JwtService): AuthPrincipal {
    val authHeader = request.headers[HttpHeaders.Authorization]
        ?: throw UnauthenticatedException("Missing Authorization header")
    if (!authHeader.startsWith("Bearer ")) {
        throw UnauthenticatedException("Malformed Authorization header, must start with Bearer ")
    }
    val token = authHeader.removePrefix("Bearer ").trim()
    return jwtService.verifyToken(token)
}

suspend fun ApplicationCall.receiveBoundedByteArray(): ByteArray {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > MAX_BODY_SIZE_BYTES) {
        throw PayloadTooLargeException()
    }

    val channel = receiveChannel()
    val packet = channel.readRemaining(MAX_BODY_SIZE_BYTES.toLong() + 1)
    val bytes = packet.readBytes()
    if (bytes.size > MAX_BODY_SIZE_BYTES) {
        throw PayloadTooLargeException()
    }
    return bytes
}
