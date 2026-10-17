package com.nnomm.database

import com.nnomm.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

class DatabaseFactory(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)
    val dataSource: DataSource by lazy { createDataSource() }

    private fun createDataSource(): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePass
            maximumPoolSize = 30
            minimumIdle = 5
            idleTimeout = 30000
            connectionTimeout = 10000
            maxLifetime = 1800000
            // Standard PostgreSQL isolation level
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            isAutoCommit = true
        }
        return HikariDataSource(hikariConfig)
    }

    fun migrate() {
        log.info("Running Flyway database migrations...")
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()
        log.info("Database migrations completed successfully.")
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            block(conn)
        }
    }

    fun <T> withTransaction(block: (Connection) -> T): T {
        return dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }
}
