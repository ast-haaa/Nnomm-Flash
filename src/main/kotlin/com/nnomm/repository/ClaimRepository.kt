package com.nnomm.repository

import com.nnomm.domain.Claim
import com.nnomm.domain.ClaimStatus
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ExpiredDealStock(
    val dealId: UUID,
    val units: Int
)

@Repository
class ClaimRepository {

    fun create(conn: Connection, claim: Claim): Claim {
        val sql = """
            INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, claim.id)
            stmt.setObject(2, claim.deal_id)
            stmt.setObject(3, claim.customer_id)
            stmt.setInt(4, claim.quantity)
            stmt.setLong(5, claim.amount_paise)
            stmt.setString(6, claim.status.name)
            stmt.setTimestamp(7, Timestamp.from(claim.expires_at))
            stmt.executeUpdate()
        }
        return claim
    }

    fun findById(conn: Connection, id: UUID): Claim? {
        val sql = "SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at FROM claims WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun findByIdForUpdate(conn: Connection, id: UUID): Claim? {
        val sql = "SELECT id, deal_id, customer_id, quantity, amount_paise, status, expires_at FROM claims WHERE id = ? FOR UPDATE"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun hasActiveClaim(conn: Connection, customerId: UUID, dealId: UUID): Boolean {
        val sql = """
            SELECT 1 FROM claims
            WHERE customer_id = ? AND deal_id = ? AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
            LIMIT 1
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, customerId)
            stmt.setObject(2, dealId)
            stmt.executeQuery().use { rs ->
                return rs.next()
            }
        }
    }

    fun updateStatus(conn: Connection, claimId: UUID, status: ClaimStatus): Boolean {
        val sql = "UPDATE claims SET status = ?, updated_at = NOW() WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status.name)
            stmt.setObject(2, claimId)
            return stmt.executeUpdate() > 0
        }
    }

    /**
     * Atomically expires overdue RESERVED claims using SKIP LOCKED.
     * Returns the aggregated units returned per deal so the caller can return stock.
     */
    fun expireOverdueReservedClaims(conn: Connection, now: Instant, limit: Int = 100): List<ExpiredDealStock> {
        val sql = """
            WITH to_expire AS (
                SELECT id, deal_id, quantity
                FROM claims
                WHERE status = 'RESERVED' AND expires_at <= ?
                ORDER BY expires_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            ),
            updated_claims AS (
                UPDATE claims c
                SET status = 'EXPIRED', updated_at = NOW()
                FROM to_expire te
                WHERE c.id = te.id
                RETURNING c.deal_id, c.quantity
            )
            SELECT deal_id, SUM(quantity)::INT AS returned_units
            FROM updated_claims
            GROUP BY deal_id
        """.trimIndent()

        val results = mutableListOf<ExpiredDealStock>()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(now))
            stmt.setInt(2, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        ExpiredDealStock(
                            dealId = rs.getObject("deal_id", UUID::class.java),
                            units = rs.getInt("returned_units")
                        )
                    )
                }
            }
        }
        return results
    }

    private fun mapRow(rs: ResultSet): Claim {
        return Claim(
            id = rs.getObject("id", UUID::class.java),
            deal_id = rs.getObject("deal_id", UUID::class.java),
            customer_id = rs.getObject("customer_id", UUID::class.java),
            quantity = rs.getInt("quantity"),
            amount_paise = rs.getLong("amount_paise"),
            status = ClaimStatus.valueOf(rs.getString("status")),
            expires_at = rs.getTimestamp("expires_at").toInstant()
        )
    }
}
