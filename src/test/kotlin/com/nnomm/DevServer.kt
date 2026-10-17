package com.nnomm

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import org.springframework.boot.runApplication

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("DevServer")
    log.info("==================================================================")
    log.info("  Starting Nnomm Spring Boot Dev Server with Embedded PostgreSQL")
    log.info("==================================================================")

    val embeddedPg = EmbeddedPostgres.builder().start()
    val jdbcUrl = embeddedPg.getJdbcUrl("postgres", "postgres")
    log.info("Embedded PostgreSQL started on {}", jdbcUrl)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down embedded PostgreSQL...")
        embeddedPg.close()
    })

    val springArgs = arrayOf(
        "--server.port=8080",
        "--spring.datasource.url=$jdbcUrl",
        "--spring.datasource.username=postgres",
        "--spring.datasource.password=postgres",
        "--nnomm.jwt-secret=nnomm-dev-secret-key-at-least-256-bits-long-for-hmac256",
        "--nnomm.webhook-secret=nnomm-webhook-secret-key-at-least-256-bits-long-for-hmac",
        *args
    )

    log.info("==================================================================")
    log.info("  🚀 Nnomm Spring Boot Server is RUNNING at: http://localhost:8080")
    log.info("  Health Check: curl http://localhost:8080/health")
    log.info("==================================================================")

    runApplication<NnommApplication>(*springArgs)
}
