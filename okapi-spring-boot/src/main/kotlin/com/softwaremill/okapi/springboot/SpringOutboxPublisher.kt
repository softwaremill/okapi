package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.TransactionalOutboxPublisher

/**
 * Spring-aware wrapper around [OutboxPublisher] that validates transactional context
 * before delegating to the core publisher.
 *
 * Ensures the outbox pattern contract: publish must happen within an active read-write transaction.
 *
 * @throws IllegalStateException if no active read-write Spring transaction is present.
 */
class SpringOutboxPublisher(delegate: OutboxPublisher) {
    private val transactionalPublisher = TransactionalOutboxPublisher(
        delegate = delegate,
        validator = SpringTransactionContextValidator(),
    )

    /**
     * Publishes a message within the currently active Spring transaction.
     * Fails fast if no read-write transaction is active.
     *
     * @throws IllegalStateException if no active read-write transaction is present.
     */
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId =
        transactionalPublisher.publish(outboxMessage, deliveryInfo)
}
