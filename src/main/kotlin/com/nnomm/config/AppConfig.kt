package com.nnomm.config

data class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePass: String,
    val jwtSecret: String,
    val webhookSecret: String,
    val claimTtlSeconds: Long
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
