package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxScheduler
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.transaction.support.TransactionTemplate

/**
 * Schedules periodic calls to [OutboxProcessor.processNext] on a dedicated daemon thread.
 *
 * Each tick is wrapped in a Spring transaction when a [TransactionTemplate] is provided.
 * Starts automatically after all Spring beans are initialized ([SmartInitializingSingleton])
 * and shuts down gracefully on context close ([DisposableBean]).
 *
 * Delegates scheduling logic to [OutboxScheduler] from okapi-core.
 */
class OutboxProcessorScheduler(
    outboxProcessor: OutboxProcessor,
    transactionTemplate: TransactionTemplate?,
    intervalMs: Long = 1_000L,
    batchSize: Int = 10,
) : SmartInitializingSingleton,
    DisposableBean {

    private val scheduler = OutboxScheduler(
        outboxProcessor = outboxProcessor,
        transactionRunner = transactionTemplate?.let { SpringTransactionRunner(it) },
        intervalMs = intervalMs,
        batchSize = batchSize,
    )

    override fun afterSingletonsInstantiated() {
        scheduler.start()
    }

    override fun destroy() {
        scheduler.stop()
    }
}
