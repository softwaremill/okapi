package com.softwaremill.okapi.core

import java.time.Clock

/**
 * Processes a single [OutboxEntry] by delegating delivery to [MessageDeliverer]
 * and applying [RetryPolicy] to determine the resulting state.
 */
class OutboxEntryProcessor(
    private val deliverer: MessageDeliverer,
    private val retryPolicy: RetryPolicy,
    private val clock: Clock,
) {
    fun process(entry: OutboxEntry): OutboxEntry {
        val now = clock.instant()
        return when (val result = deliverer.deliver(entry)) {
            is DeliveryResult.Success -> entry.toDelivered(now)
            is DeliveryResult.RetriableFailure ->
                if (retryPolicy.shouldRetry(entry.retries)) {
                    entry.retry(now, result.error)
                } else {
                    entry.toFailed(now, result.error)
                }
            is DeliveryResult.PermanentFailure -> entry.toFailed(now, result.error)
        }
    }
}
