package com.nnomm.security

import com.nnomm.config.AppConfig
import com.nnomm.domain.UnauthenticatedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

@Component
class HmacService(private val webhookSecret: String) {

    @Autowired
    constructor(config: AppConfig) : this(config.webhookSecret)

    fun verifySignature(header: String?, rawBody: ByteArray, currentEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        if (header.isNullOrBlank()) {
            throw UnauthenticatedException("Missing X-Payment-Signature header")
        }

        val parts = header.split(",").associate {
            val keyVal = it.split("=", limit = 2)
            if (keyVal.size == 2) keyVal[0].trim() to keyVal[1].trim() else "" to ""
        }

        val tStr = parts["t"] ?: throw UnauthenticatedException("Malformed X-Payment-Signature: missing t")
        val v1 = parts["v1"] ?: throw UnauthenticatedException("Malformed X-Payment-Signature: missing v1")

        val timestamp = tStr.toLongOrNull() ?: throw UnauthenticatedException("Malformed X-Payment-Signature: invalid t timestamp")

        // Reject if t is more than 300 seconds from our clock
        if (abs(currentEpochSeconds - timestamp) > 300) {
            throw UnauthenticatedException("Webhook timestamp too old or too far in future (> 300s)")
        }

        // Signed message: t + "." + raw request body bytes
        val messagePrefix = "$timestamp.".toByteArray(Charsets.UTF_8)
        val fullMessage = messagePrefix + rawBody

        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val computedBytes = mac.doFinal(fullMessage)
        val computedHex = computedBytes.joinToString("") { "%02x".format(it) }

        // Constant time comparison to prevent timing attacks
        if (!MessageDigest.isEqual(computedHex.toByteArray(Charsets.UTF_8), v1.toByteArray(Charsets.UTF_8))) {
            throw UnauthenticatedException("Invalid webhook signature")
        }
    }

    fun computeSignature(timestampSeconds: Long, rawBody: ByteArray): String {
        val messagePrefix = "$timestampSeconds.".toByteArray(Charsets.UTF_8)
        val fullMessage = messagePrefix + rawBody
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(fullMessage)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "t=$timestampSeconds,v1=$hex"
    }
}
