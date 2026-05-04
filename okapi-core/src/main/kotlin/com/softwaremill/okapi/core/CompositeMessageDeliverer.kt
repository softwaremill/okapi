package com.softwaremill.okapi.core

/**
 * Routes delivery to the correct [MessageDeliverer] based on [OutboxEntry.deliveryType].
 *
 * Fails permanently for any entry whose type has no registered deliverer,
 * rather than throwing — so the outbox processor can move it to FAILED
 * and continue with remaining entries.
 */
class CompositeMessageDeliverer(deliverers: List<MessageDeliverer>) : MessageDeliverer {
    override val type: String = "composite"

    private val registry: Map<String, MessageDeliverer> = deliverers.associateBy { it.type }

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        val messageDeliverer = registry[entry.deliveryType]
            ?: return DeliveryResult.PermanentFailure("No deliverer registered for type '${entry.deliveryType}'")
        return messageDeliverer.deliver(entry)
    }

    /**
     * Groups entries by [OutboxEntry.deliveryType] and delegates each sub-batch
     * to the matching deliverer's [MessageDeliverer.deliverBatch]. Results are
     * re-assembled in original input order.
     *
     * Entries whose type has no registered deliverer are mapped to
     * [DeliveryResult.PermanentFailure] (consistent with [deliver]).
     */
    override fun deliverBatch(entries: List<OutboxEntry>): List<Pair<OutboxEntry, DeliveryResult>> {
        if (entries.isEmpty()) return emptyList()

        val resultByEntry: Map<OutboxEntry, DeliveryResult> = entries
            .groupBy { it.deliveryType }
            .flatMap { (type, group) ->
                val deliverer = registry[type]
                if (deliverer != null) {
                    deliverer.deliverBatch(group)
                } else {
                    group.map { it to DeliveryResult.PermanentFailure("No deliverer registered for type '$type'") }
                }
            }
            .toMap()

        return entries.map { entry -> entry to (resultByEntry[entry] ?: error("missing result for entry ${entry.outboxId}")) }
    }
}
