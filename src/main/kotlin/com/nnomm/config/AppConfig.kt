package com.nnomm.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
data class AppConfig(
    @Value("\${server.port:8080}") val port: Int = 8080,
    @Value("\${spring.datasource.url:jdbc:postgresql://localhost:5432/nnomm}") val databaseUrl: String = "jdbc:postgresql://localhost:5432/nnomm",
    @Value("\${spring.datasource.username:postgres}") val databaseUser: String = "postgres",
    @Value("\${spring.datasource.password:postgres}") val databasePass: String = "postgres",
    @Value("\${nnomm.jwt-secret:default_insecure_jwt_secret_for_local_dev_only_32_bytes_min}") val jwtSecret: String = "default_insecure_jwt_secret_for_local_dev_only_32_bytes_min",
    @Value("\${nnomm.webhook-secret:default_insecure_webhook_secret_for_dev_min_32_bytes}") val webhookSecret: String = "default_insecure_webhook_secret_for_dev_min_32_bytes",
    @Value("\${nnomm.claim-ttl-seconds:600}") val claimTtlSeconds: Long = 600L
) {
    companion object {
        fun fromEnv(): AppConfig {
            val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
            val dbUrl = System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/nnomm"
            val dbUser = System.getenv("DATABASE_USER") ?: "postgres"
            val dbPass = System.getenv("DATABASE_PASSWORD") ?: "postgres"
            val jwtSecret = System.getenv("JWT_SECRET") ?: "default_insecure_jwt_secret_for_local_dev_only_32_bytes_min"
            val webhookSecret = System.getenv("WEBHOOK_SECRET") ?: "default_insecure_webhook_secret_for_dev_min_32_bytes"
            val claimTtl = System.getenv("CLAIM_TTL_SECONDS")?.toLongOrNull() ?: 600L

            return AppConfig(
                port = port,
                databaseUrl = dbUrl,
                databaseUser = dbUser,
                databasePass = dbPass,
                jwtSecret = jwtSecret,
                webhookSecret = webhookSecret,
                claimTtlSeconds = claimTtl
            )
        }
    }
}
