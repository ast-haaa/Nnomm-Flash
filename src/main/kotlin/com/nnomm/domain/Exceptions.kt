package com.nnomm.domain

open class AppException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : RuntimeException(message)

class ValidationException(message: String) : AppException(
    400,
    "VALIDATION_FAILED",
    message
)

class IdempotencyKeyInvalidException(message: String = "Idempotency-Key is missing or malformed") : AppException(
    400,
    "IDEMPOTENCY_KEY_INVALID",
    message
)

class UnauthenticatedException(message: String = "Bad or expired token, or a bad webhook signature") : AppException(
    401,
    "UNAUTHENTICATED",
    message
)

class ForbiddenException(message: String = "The caller's role can't use this endpoint") : AppException(
    403,
    "FORBIDDEN",
    message
)

class NotFoundException(message: String = "It doesn't exist, or the caller may not see it") : AppException(
    404,
    "NOT_FOUND",
    message
)

class SoldOutException(message: String = "Not enough units left for this quantity") : AppException(
    409,
    "SOLD_OUT",
    message
)

class AlreadyClaimedException(message: String = "The customer already holds an active claim on this deal") : AppException(
    409,
    "ALREADY_CLAIMED",
    message
)

class DealNotLiveException(message: String = "The deal hasn't started yet, or has ended") : AppException(
    409,
    "DEAL_NOT_LIVE",
    message
)

class RequestInProgressException(message: String = "A request with the same key is still running") : AppException(
    409,
    "REQUEST_IN_PROGRESS",
    message
)

class PayloadTooLargeException(message: String = "The body is over 16 KB") : AppException(
    413,
    "PAYLOAD_TOO_LARGE",
    message
)

class IdempotencyKeyReusedException(message: String = "Same key, different request") : AppException(
    422,
    "IDEMPOTENCY_KEY_REUSED",
    message
)

class InternalException(message: String = "Internal server error") : AppException(
    500,
    "INTERNAL",
    message
)
