package com.softwaremill.okapi.spring

import com.softwaremill.okapi.core.OutboxProcessor
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic calls to [OutboxProcessor.processNext] on a dedicated daemon thread.
 *
 * Each tick is wrapped in a Spring transaction when a [PlatformTransactionManager] is provided.
 * Starts automatically after all Spring beans are initialized ([SmartInitializingSingleton])
 * and shuts down gracefully on context close ([DisposableBean]).
 */
class OutboxProcessorScheduler(
    private val outboxProcessor: OutboxProcessor,
    private val transactionTemplate: TransactionTemplate?,
    private val intervalMs: Long = 1_000L,
    private val batchSize: Int = 10,
) : SmartInitializingSingleton,
    DisposableBean {

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-processor").apply { isDaemon = true }
        }

    override fun afterSingletonsInstantiated() {
        scheduler.scheduleWithFixedDelay(
            ::tick,
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun tick() {
        if (transactionTemplate != null) {
            transactionTemplate.execute { outboxProcessor.processNext(batchSize) }
        } else {
            outboxProcessor.processNext(batchSize)
        }
    }

    override fun destroy() {
        scheduler.shutdown()
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            scheduler.shutdownNow()
        }
    }
}
