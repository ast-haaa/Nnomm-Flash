package com.nnomm.repository

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class IdempotencyRecord(
    val key: String,
    val customerId: UUID,
    val dealId: UUID,
    val quantity: Int,
    val statusCode: Int?,
    val responseBody: String?,
    val createdAt: Instant,
    val lockedUntil: Instant
)

class IdempotencyRepository {

    fun findForUpdate(conn: Connection, customerId: UUID, key: String): IdempotencyRecord? {
        val sql = """
            SELECT key, customer_id, deal_id, quantity, status_code, response_body, created_at, locked_until
            FROM idempotency_keys
            WHERE customer_id = ? AND key = ?
            FOR UPDATE
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun insertInProgress(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int,
        lockedUntil: Instant
    ): Boolean {
        val sql = """
            INSERT INTO idempotency_keys (customer_id, key, deal_id, quantity, locked_until, created_at)
            VALUES (?, ?, ?, ?, ?, NOW())
            ON CONFLICT (customer_id, key) DO NOTHING
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            stmt.setObject(3, dealId)
            stmt.setInt(4, quantity)
            stmt.setTimestamp(5, Timestamp.from(lockedUntil))
            return stmt.executeUpdate() > 0
        }
    }

    fun renewLock(
        conn: Connection,
        customerId: UUID,
        key: String,
        dealId: UUID,
        quantity: Int,
        lockedUntil: Instant
    ): Boolean {
        val sql = """
            UPDATE idempotency_keys
            SET locked_until = ?, deal_id = ?, quantity = ?, status_code = NULL, response_body = NULL
            WHERE customer_id = ? AND key = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(lockedUntil))
            stmt.setObject(2, dealId)
            stmt.setInt(3, quantity)
            stmt.setObject(4, customerId)
            stmt.setString(5, key)
            return stmt.executeUpdate() > 0
        }
    }

    fun saveResult(
        conn: Connection,
        customerId: UUID,
        key: String,
        statusCode: Int,
        responseBody: String
    ): Boolean {
        val sql = """
            UPDATE idempotency_keys
            SET status_code = ?, response_body = ?
            WHERE customer_id = ? AND key = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, statusCode)
            stmt.setString(2, responseBody)
            stmt.setObject(3, customerId)
            stmt.setString(4, key)
            return stmt.executeUpdate() > 0
        }
    }

    fun delete(conn: Connection, customerId: UUID, key: String): Boolean {
        val sql = "DELETE FROM idempotency_keys WHERE customer_id = ? AND key = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setString(2, key)
            return stmt.executeUpdate() > 0
        }
    }

    private fun mapRow(rs: ResultSet): IdempotencyRecord {
        val sc = rs.getInt("status_code")
        val statusCode = if (rs.wasNull()) null else sc
        return IdempotencyRecord(
            key = rs.getString("key"),
            customerId = rs.getObject("customer_id", UUID::class.java),
            dealId = rs.getObject("deal_id", UUID::class.java),
            quantity = rs.getInt("quantity"),
            statusCode = statusCode,
            responseBody = rs.getString("response_body"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lockedUntil = rs.getTimestamp("locked_until").toInstant()
        )
    }
}
