package com.nnomm.service

import com.nnomm.database.DatabaseFactory
import com.nnomm.repository.ClaimRepository
import com.nnomm.repository.DealRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

class ClaimExpiryWorker(
    private val db: DatabaseFactory,
    private val claimRepository: ClaimRepository = ClaimRepository(),
    private val dealRepository: DealRepository = DealRepository(),
    private val sweepIntervalMs: Long = 1000L
) {
    private val log = LoggerFactory.getLogger(ClaimExpiryWorker::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (job != null) return
        log.info("Starting background ClaimExpiryWorker with sweep interval {}ms", sweepIntervalMs)
        job = scope.launch {
            while (isActive) {
                try {
                    sweepExpiredClaims()
                } catch (e: Exception) {
                    log.error("Error during claim expiry sweep", e)
                }
                delay(sweepIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        log.info("ClaimExpiryWorker stopped")
    }

    fun sweepExpiredClaims(): Int {
        val now = Instant.now()
        return db.withTransaction { conn ->
            val expiredBatches = claimRepository.expireOverdueReservedClaims(conn, now, 100)
            var totalExpiredUnits = 0
            for (batch in expiredBatches) {
                dealRepository.incrementStock(conn, batch.dealId, batch.units)
                totalExpiredUnits += batch.units
            }
            if (totalExpiredUnits > 0) {
                log.info("Expired reserved claims reclaimed {} units across {} deals", totalExpiredUnits, expiredBatches.size)
            }
            totalExpiredUnits
        }
    }
}
