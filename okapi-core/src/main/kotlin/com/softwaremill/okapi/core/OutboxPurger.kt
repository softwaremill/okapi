package com.softwaremill.okapi.core

import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically removes DELIVERED outbox entries older than [retentionDuration].
 *
 * Runs on a single daemon thread with explicit [start]/[stop] lifecycle.
 * Delegates to [OutboxStore.removeDeliveredBefore] — works with any storage adapter.
 */
class OutboxPurger(
    private val outboxStore: OutboxStore,
    private val retentionDuration: Duration = Duration.ofDays(7),
    private val intervalMs: Long = 3_600_000L,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-purger").apply { isDaemon = true }
        }

    fun start() {
        scheduler.scheduleWithFixedDelay(
            ::tick,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }

    private fun tick() {
        outboxStore.removeDeliveredBefore(clock.instant().minus(retentionDuration))
    }
}
