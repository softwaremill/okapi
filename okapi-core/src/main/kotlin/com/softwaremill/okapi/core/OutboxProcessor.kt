package com.softwaremill.okapi.core

/**
 * Orchestrates a single processing cycle: claims pending entries from [OutboxStore],
 * delegates each to [OutboxEntryProcessor], and persists the result.
 *
 * Transaction management is the caller's responsibility.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val entryProcessor: OutboxEntryProcessor,
) {
    @JvmOverloads
    fun processNext(limit: Int = 10) {
        store.claimPending(limit).forEach { entry ->
            val updated = entryProcessor.process(entry)
            store.updateAfterProcessing(updated)
        }
    }
}
