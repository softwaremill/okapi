package com.softwaremill.okapi.core

import java.time.Clock

/**
 * Publishes outbox messages by persisting them as PENDING entries via [OutboxStore].
 *
 * This class does NOT manage transactions. The caller is responsible for ensuring
 * that [publish] is invoked within the same transaction as the business operation —
 * this is fundamental to the transactional outbox pattern.
 *
 * Framework-specific modules (e.g. `okapi-spring`) may provide wrappers that
 * validate transactional context before delegating here.
 */
class OutboxPublisher(
    private val outboxStore: OutboxStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId = OutboxEntry
        .createPending(outboxMessage, deliveryInfo, clock.instant())
        .also(outboxStore::persist)
        .outboxId
}
