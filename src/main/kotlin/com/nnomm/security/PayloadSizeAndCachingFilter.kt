package com.nnomm.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.domain.ErrorDetail
import com.nnomm.domain.ErrorResponse
import com.nnomm.domain.PayloadTooLargeException
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

const val MAX_BODY_SIZE_BYTES = 16 * 1024 // 16 KB

class CachedBodyHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    val cachedBody: ByteArray

    init {
        val buffer = ByteArrayOutputStream()
        val inputStream = request.inputStream
        val temp = ByteArray(1024)
        var bytesRead: Int
        var totalBytes = 0

        while (inputStream.read(temp).also { bytesRead = it } != -1) {
            totalBytes += bytesRead
            if (totalBytes > MAX_BODY_SIZE_BYTES) {
                throw PayloadTooLargeException()
            }
            buffer.write(temp, 0, bytesRead)
        }
        cachedBody = buffer.toByteArray()
    }

    override fun getInputStream(): ServletInputStream {
        val byteArrayInputStream = ByteArrayInputStream(cachedBody)
        return object : ServletInputStream() {
            override fun isFinished(): Boolean = byteArrayInputStream.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener?) {}
            override fun read(): Int = byteArrayInputStream.read()
        }
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PayloadSizeAndCachingFilter(
    private val objectMapper: ObjectMapper = ObjectMapper()
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val contentLength = request.contentLengthLong
        if (contentLength > MAX_BODY_SIZE_BYTES) {
            sendPayloadTooLarge(response)
            return
        }

        val cachedRequest = try {
            CachedBodyHttpServletRequest(request)
        } catch (e: PayloadTooLargeException) {
            sendPayloadTooLarge(response)
            return
        }

        filterChain.doFilter(cachedRequest, response)
    }

    private fun sendPayloadTooLarge(response: HttpServletResponse) {
        response.status = 413
        response.contentType = "application/json"
        val errorJson = objectMapper.writeValueAsString(
            ErrorResponse(ErrorDetail(code = "PAYLOAD_TOO_LARGE", message = "The body is over 16 KB"))
        )
        response.writer.write(errorJson)
    }
}
