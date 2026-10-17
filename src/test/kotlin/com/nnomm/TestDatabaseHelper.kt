package com.nnomm

import com.nnomm.config.AppConfig
import com.nnomm.database.DatabaseFactory
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection

object TestDatabaseHelper {
    private val log = LoggerFactory.getLogger(TestDatabaseHelper::class.java)

    private var pgContainer: PostgreSQLContainer<*>? = null
    private var embeddedPg: EmbeddedPostgres? = null

    val config: AppConfig by lazy {
        initDatabase()
    }

    val dbFactory: DatabaseFactory by lazy {
        val factory = DatabaseFactory(config)
        factory.migrate()
        factory
    }

    private fun initDatabase(): AppConfig {
        val envUrl = System.getenv("DATABASE_URL")
        if (!envUrl.isNullOrBlank()) {
            log.info("Using DATABASE_URL from environment: {}", envUrl)
            return AppConfig.fromEnv()
        }

        val dockerAvailable = try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (_: Throwable) {
            false
        }

        if (dockerAvailable) {
            log.info("Docker is available, starting Testcontainers PostgreSQL 15...")
            val container = PostgreSQLContainer("postgres:15-alpine")
                .withDatabaseName("nnomm_test")
                .withUsername("postgres")
                .withPassword("postgres")
            container.start()
            pgContainer = container

            return AppConfig(
                port = 8080,
                databaseUrl = container.jdbcUrl,
                databaseUser = container.username,
                databasePass = container.password,
                jwtSecret = "super-secret-jwt-key-for-testing-at-least-32-chars-long",
                webhookSecret = "super-secret-webhook-key-for-testing-at-least-32-chars",
                claimTtlSeconds = 600L
            )
        }

        log.info("Docker not detected, starting Embedded real PostgreSQL 15 engine...")
        val pg = EmbeddedPostgres.builder().start()
        embeddedPg = pg

        return AppConfig(
            port = 8080,
            databaseUrl = pg.getJdbcUrl("postgres", "postgres"),
            databaseUser = "postgres",
            databasePass = "postgres",
            jwtSecret = "super-secret-jwt-key-for-testing-at-least-32-chars-long",
            webhookSecret = "super-secret-webhook-key-for-testing-at-least-32-chars",
            claimTtlSeconds = 600L
        )
    }

    fun cleanTables() {
        dbFactory.withConnection { conn: Connection ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE payment_events, idempotency_keys, claims, deals CASCADE;")
            }
        }
    }
}
