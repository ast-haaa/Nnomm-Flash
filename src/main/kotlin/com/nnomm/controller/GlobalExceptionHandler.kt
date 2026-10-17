package com.nnomm.controller

import com.nnomm.domain.AppException
import com.nnomm.domain.ErrorDetail
import com.nnomm.domain.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = ex.code,
                message = ex.message
            )
        )
        return ResponseEntity.status(ex.statusCode).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception occurred", ex)
        val errorResponse = ErrorResponse(
            error = ErrorDetail(
                code = "INTERNAL",
                message = ex.message ?: "Internal server error"
            )
        )
        return ResponseEntity.status(500).body(errorResponse)
    }
}
