package com.softwaremill.okapi.core

/**
 * Transport abstraction for delivering an [OutboxEntry].
 *
 * [type] must match the [DeliveryInfo.type] of the entries this deliverer handles.
 * [CompositeMessageDeliverer] uses this to route entries to the correct implementation.
 */
interface MessageDeliverer {
    val type: String

    fun deliver(entry: OutboxEntry): DeliveryResult
}
