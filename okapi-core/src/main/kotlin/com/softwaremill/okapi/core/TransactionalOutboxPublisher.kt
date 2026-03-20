package com.softwaremill.okapi.core

/**
 * Wraps [OutboxPublisher] with transaction context validation.
 *
 * Ensures the outbox pattern contract: [publish] must happen within an active
 * read-write transaction. Delegates actual validation to [TransactionContextValidator],
 * which is implemented per framework.
 *
 * @throws IllegalStateException if no active read-write transaction is present.
 */
class TransactionalOutboxPublisher(
    private val delegate: OutboxPublisher,
    private val validator: TransactionContextValidator,
) {
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId {
        check(validator.isInActiveReadWriteTransaction()) {
            "No active read-write transaction. Ensure that publish() is called within a transactional context."
        }
        return delegate.publish(outboxMessage, deliveryInfo)
    }
}
