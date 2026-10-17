package com.nnomm.repository

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PaymentEventRepository {

    fun isEventProcessed(conn: Connection, eventId: String): Boolean {
        val sql = "SELECT 1 FROM payment_events WHERE event_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventId)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    fun recordEvent(
        conn: Connection,
        eventId: String,
        claimId: UUID,
        type: String,
        amountPaise: Long,
        occurredAt: Instant,
        resultStatus: String
    ): Boolean {
        val sql = """
            INSERT INTO payment_events (event_id, claim_id, type, amount_paise, occurred_at, processed_at, result_status)
            VALUES (?, ?, ?, ?, ?, NOW(), ?)
            ON CONFLICT (event_id) DO NOTHING
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventId)
            stmt.setObject(2, claimId)
            stmt.setString(3, type)
            stmt.setLong(4, amountPaise)
            stmt.setTimestamp(5, Timestamp.from(occurredAt))
            stmt.setString(6, resultStatus)
            return stmt.executeUpdate() > 0
        }
    }
}
