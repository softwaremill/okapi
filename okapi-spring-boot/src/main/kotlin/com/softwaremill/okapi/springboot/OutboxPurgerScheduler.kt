package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxPurger
import com.softwaremill.okapi.core.OutboxPurgerConfig
import com.softwaremill.okapi.core.OutboxStore
import org.springframework.context.SmartLifecycle
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Spring lifecycle wrapper for [OutboxPurger].
 *
 * Uses [SmartLifecycle] for phase-ordered startup/shutdown.
 * Enabled by default; disable with `okapi.purger.enabled=false`.
 */
class OutboxPurgerScheduler(
    outboxStore: OutboxStore,
    transactionTemplate: TransactionTemplate? = null,
    config: OutboxPurgerConfig = OutboxPurgerConfig(),
    clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {

    private val purger = OutboxPurger(
        outboxStore = outboxStore,
        transactionRunner = transactionTemplate?.let { SpringTransactionRunner(it) },
        config = config,
        clock = clock,
    )

    override fun start() {
        purger.start()
    }

    override fun stop() {
        purger.stop()
    }

    override fun stop(callback: Runnable) {
        try {
            purger.stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = purger.isRunning()

    override fun getPhase(): Int = PURGER_PHASE

    companion object {
        /** Start late (after app beans), stop early (before app beans). */
        const val PURGER_PHASE = Integer.MAX_VALUE - 1024
    }
}
