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
    val supportedDeliveryTypes: Set<String> =
        if (deliverer is CompositeMessageDeliverer) deliverer.supportedDeliveryTypes else setOf(deliverer.type)

    fun process(entry: OutboxEntry): OutboxEntry {
        check(entry.deliveryType in supportedDeliveryTypes) {
            "No deliverer registered for type '${entry.deliveryType}'"
        }
        return applyResult(entry, deliverer.deliver(entry), clock.instant())
    }

    /**
     * Processes a batch of entries via [MessageDeliverer.deliverBatch], applying
     * the retry policy per result. Returns processed entries in the same order
     * as the input list.
     */
    fun processBatch(entries: List<OutboxEntry>): List<OutboxEntry> {
        if (entries.isEmpty()) return emptyList()
        check(entries.all { it.deliveryType in supportedDeliveryTypes }) {
            "Batch contains an entry with no registered deliverer"
        }
        val now = clock.instant()
        return deliverer.deliverBatch(entries).map { (entry, result) -> applyResult(entry, result, now) }
    }

    private fun applyResult(entry: OutboxEntry, result: DeliveryResult, now: java.time.Instant): OutboxEntry = when (result) {
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
