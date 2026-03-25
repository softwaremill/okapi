package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxPurger
import com.softwaremill.okapi.core.OutboxStore
import org.springframework.context.SmartLifecycle
import java.time.Clock
import java.time.Duration

/**
 * Spring lifecycle wrapper for [OutboxPurger].
 *
 * Uses [SmartLifecycle] for phase-ordered startup/shutdown and async stop support.
 * Enabled by default; disable with `okapi.purger.enabled=false`.
 */
class OutboxPurgerScheduler(
    outboxStore: OutboxStore,
    retentionDays: Long = 7,
    intervalMinutes: Long = 60,
    batchSize: Int = 100,
    clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {

    private val purger = OutboxPurger(
        outboxStore = outboxStore,
        retentionDuration = Duration.ofDays(retentionDays),
        intervalMs = intervalMinutes * 60 * 1_000,
        batchSize = batchSize,
        clock = clock,
    )

    override fun start() {
        purger.start()
    }

    override fun stop() {
        purger.stop()
    }

    override fun stop(callback: Runnable) {
        purger.stop()
        callback.run()
    }

    override fun isRunning(): Boolean = purger.isRunning()

    override fun getPhase(): Int = PURGER_PHASE

    companion object {
        /** Start late (after app beans), stop early (before app beans). */
        const val PURGER_PHASE = Integer.MAX_VALUE - 1024
    }
}
