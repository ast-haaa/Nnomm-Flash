package com.nnomm

import com.nnomm.config.AppConfig
import com.nnomm.database.DatabaseFactory
import com.nnomm.plugins.configureRouting
import com.nnomm.plugins.configureSerialization
import com.nnomm.plugins.configureStatusPages
import com.nnomm.security.HmacService
import com.nnomm.security.JwtService
import com.nnomm.service.ClaimExpiryWorker
import com.nnomm.service.ClaimService
import com.nnomm.service.DealService
import com.nnomm.service.PaymentWebhookService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("Application")
    val config = AppConfig.fromEnv()
    log.info("Starting Nnomm Claim Service on port {}", config.port)

    val db = DatabaseFactory(config)
    db.migrate()

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config, db)
    }

    server.start(wait = true)
}

fun Application.module(
    config: AppConfig,
    db: DatabaseFactory,
    startWorker: Boolean = true
) {
    val jwtService = JwtService(config.jwtSecret)
    val hmacService = HmacService(config.webhookSecret)

    val dealService = DealService(db)
    val claimService = ClaimService(config, db)
    val paymentWebhookService = PaymentWebhookService(hmacService, db)
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val expiryWorker = ClaimExpiryWorker(db)
    if (startWorker) {
        expiryWorker.start()
        environment.monitor.subscribe(ApplicationStopping) {
            expiryWorker.stop()
        }
    }

    configureSerialization()
    configureStatusPages()
    configureRouting(
        dealService = dealService,
        claimService = claimService,
        paymentWebhookService = paymentWebhookService,
        jwtService = jwtService,
        json = json
    )
}
