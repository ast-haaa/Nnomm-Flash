package com.nnomm.domain

import io.ktor.http.HttpStatusCode

open class AppException(
    val statusCode: HttpStatusCode,
    val code: String,
    override val message: String
) : RuntimeException(message)

class ValidationException(message: String) : AppException(
    HttpStatusCode.BadRequest,
    "VALIDATION_FAILED",
    message
)

class IdempotencyKeyInvalidException(message: String = "Idempotency-Key is missing or malformed") : AppException(
    HttpStatusCode.BadRequest,
    "IDEMPOTENCY_KEY_INVALID",
    message
)

class UnauthenticatedException(message: String = "Bad or expired token, or a bad webhook signature") : AppException(
    HttpStatusCode.Unauthorized,
    "UNAUTHENTICATED",
    message
)

class ForbiddenException(message: String = "The caller's role can't use this endpoint") : AppException(
    HttpStatusCode.Forbidden,
    "FORBIDDEN",
    message
)

class NotFoundException(message: String = "It doesn't exist, or the caller may not see it") : AppException(
    HttpStatusCode.NotFound,
    "NOT_FOUND",
    message
)

class SoldOutException(message: String = "Not enough units left for this quantity") : AppException(
    HttpStatusCode.Conflict,
    "SOLD_OUT",
    message
)

class AlreadyClaimedException(message: String = "The customer already holds an active claim on this deal") : AppException(
    HttpStatusCode.Conflict,
    "ALREADY_CLAIMED",
    message
)

class DealNotLiveException(message: String = "The deal hasn't started yet, or has ended") : AppException(
    HttpStatusCode.Conflict,
    "DEAL_NOT_LIVE",
    message
)

class RequestInProgressException(message: String = "A request with the same key is still running") : AppException(
    HttpStatusCode.Conflict,
    "REQUEST_IN_PROGRESS",
    message
)

class PayloadTooLargeException(message: String = "The body is over 16 KB") : AppException(
    HttpStatusCode.PayloadTooLarge,
    "PAYLOAD_TOO_LARGE",
    message
)

class IdempotencyKeyReusedException(message: String = "Same key, different request") : AppException(
    HttpStatusCode.UnprocessableEntity,
    "IDEMPOTENCY_KEY_REUSED",
    message
)

class InternalException(message: String = "Internal server error") : AppException(
    HttpStatusCode.InternalServerError,
    "INTERNAL",
    message
)
