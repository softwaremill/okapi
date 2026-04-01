package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxScheduler
import com.softwaremill.okapi.core.OutboxSchedulerConfig
import org.springframework.context.SmartLifecycle
import org.springframework.transaction.support.TransactionTemplate

/**
 * Spring lifecycle wrapper for [OutboxScheduler].
 *
 * Uses [SmartLifecycle] for phase-ordered startup/shutdown.
 * Starts before [OutboxPurgerScheduler] and stops after it,
 * ensuring entries are processed before they can be purged.
 *
 * Enabled by default; disable with `okapi.processor.enabled=false`.
 */
class OutboxProcessorScheduler(
    outboxProcessor: OutboxProcessor,
    transactionTemplate: TransactionTemplate?,
    config: OutboxSchedulerConfig = OutboxSchedulerConfig(),
) : SmartLifecycle {

    private val scheduler = OutboxScheduler(
        outboxProcessor = outboxProcessor,
        transactionRunner = transactionTemplate?.let { SpringTransactionRunner(it) },
        config = config,
    )

    override fun start() {
        scheduler.start()
    }

    override fun stop() {
        scheduler.stop()
    }

    override fun stop(callback: Runnable) {
        try {
            scheduler.stop()
        } finally {
            callback.run()
        }
    }

    override fun isRunning(): Boolean = scheduler.isRunning()

    override fun getPhase(): Int = PROCESSOR_PHASE

    companion object {
        const val PROCESSOR_PHASE = Integer.MAX_VALUE - 2048
    }
}
