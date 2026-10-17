package com.nnomm.plugins

import com.nnomm.domain.AppException
import com.nnomm.domain.ErrorDetail
import com.nnomm.domain.ErrorResponse
import com.nnomm.domain.InternalException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureStatusPages() {
    val log = LoggerFactory.getLogger("StatusPages")
    val json = Json { ignoreUnknownKeys = true }

    install(StatusPages) {
        exception<AppException> { call, cause ->
            val errorResponse = ErrorResponse(ErrorDetail(code = cause.code, message = cause.message))
            call.response.status(cause.statusCode)
            call.respondText(
                text = json.encodeToString(errorResponse),
                contentType = ContentType.Application.Json,
                status = cause.statusCode
            )
        }

        exception<Throwable> { call, cause ->
            log.error("Unhandled exception processing request", cause)
            val errorResponse = ErrorResponse(ErrorDetail(code = "INTERNAL", message = "Unexpected error"))
            call.respondText(
                text = json.encodeToString(errorResponse),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}
