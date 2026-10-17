package com.nnomm.repository

import com.nnomm.domain.Deal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class DealRepository {

    fun create(conn: Connection, deal: Deal): Deal {
        val sql = """
            INSERT INTO deals (id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, deal.id)
            stmt.setObject(2, deal.restaurant_id)
            stmt.setString(3, deal.title)
            stmt.setLong(4, deal.price_paise)
            stmt.setInt(5, deal.stock_total)
            stmt.setInt(6, deal.stock_available)
            stmt.setTimestamp(7, Timestamp.from(deal.starts_at))
            stmt.setTimestamp(8, Timestamp.from(deal.ends_at))
            stmt.executeUpdate()
        }
        return deal
    }

    fun findById(conn: Connection, id: UUID): Deal? {
        val sql = "SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at FROM deals WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun findByIdForUpdate(conn: Connection, id: UUID): Deal? {
        val sql = "SELECT id, restaurant_id, title, price_paise, stock_total, stock_available, starts_at, ends_at FROM deals WHERE id = ? FOR UPDATE"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, id)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    fun decrementStock(conn: Connection, dealId: UUID, quantity: Int): Boolean {
        val sql = """
            UPDATE deals
            SET stock_available = stock_available - ?
            WHERE id = ? AND stock_available >= ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            stmt.setInt(3, quantity)
            return stmt.executeUpdate() > 0
        }
    }

    fun incrementStock(conn: Connection, dealId: UUID, quantity: Int): Boolean {
        val sql = """
            UPDATE deals
            SET stock_available = stock_available + ?
            WHERE id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, quantity)
            stmt.setObject(2, dealId)
            return stmt.executeUpdate() > 0
        }
    }

    private fun mapRow(rs: ResultSet): Deal {
        return Deal(
            id = rs.getObject("id", UUID::class.java),
            restaurant_id = rs.getObject("restaurant_id", UUID::class.java),
            title = rs.getString("title"),
            price_paise = rs.getLong("price_paise"),
            stock_total = rs.getInt("stock_total"),
            stock_available = rs.getInt("stock_available"),
            starts_at = rs.getTimestamp("starts_at").toInstant(),
            ends_at = rs.getTimestamp("ends_at").toInstant()
        )
    }
}
