package com.softwaremill.okapi.core

/**
 * Contract for delivery metadata attached to an [OutboxEntry].
 *
 * Each transport module (HTTP, Kafka, etc.) provides its own implementation.
 * The serialized JSON is stored opaquely in [OutboxEntry.deliveryMetadata] —
 * the storage layer never interprets it.
 *
 * [type] is a stable, unique identifier for the transport (e.g. "http", "kafka").
 * It must be included in [serialize] so that [CompositeMessageDeliverer] can
 * route entries to the correct [MessageDeliverer] without deserializing the full metadata.
 *
 * Implementors are responsible for:
 * - declaring a unique [type] constant
 * - serializing to JSON (including [type]) in [serialize]
 * - providing a companion `deserialize(json: String)` for use in their [MessageDeliverer]
 */
interface DeliveryInfo {
    val type: String

    fun serialize(): String
}
