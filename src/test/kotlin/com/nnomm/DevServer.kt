package com.nnomm

import com.nnomm.config.AppConfig
import com.nnomm.database.DatabaseFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("DevServer")
    log.info("==================================================================")
    log.info("  Starting Nnomm Local Dev Environment with Embedded PostgreSQL")
    log.info("==================================================================")

    val embeddedPg = EmbeddedPostgres.builder().start()
    val jdbcUrl = embeddedPg.getJdbcUrl("postgres", "postgres")
    log.info("Embedded PostgreSQL started on {}", jdbcUrl)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down embedded PostgreSQL...")
        embeddedPg.close()
    })

    val config = AppConfig(
        port = 8080,
        databaseUrl = jdbcUrl,
        databaseUser = "postgres",
        databasePass = "postgres",
        jwtSecret = "nnomm-dev-secret-key-at-least-256-bits-long-for-hmac256",
        webhookSecret = "nnomm-webhook-secret-key-at-least-256-bits-long-for-hmac",
        claimTtlSeconds = 600L
    )

    val db = DatabaseFactory(config)
    log.info("Running Flyway migrations on embedded database...")
    db.migrate()

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, db, startWorker = true)
    }

    log.info("==================================================================")
    log.info("  🚀 Nnomm Server is RUNNING at: http://localhost:8080")
    log.info("  Health Check: curl http://localhost:8080/health")
    log.info("==================================================================")

    server.start(wait = true)
}
