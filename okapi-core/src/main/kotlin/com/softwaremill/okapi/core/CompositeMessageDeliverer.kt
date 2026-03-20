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
}
