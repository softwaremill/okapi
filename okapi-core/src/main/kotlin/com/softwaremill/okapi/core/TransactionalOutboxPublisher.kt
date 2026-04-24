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
    @JvmName("publish")
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId {
        check(validator.isInActiveReadWriteTransaction()) { validator.failureMessage }
        return delegate.publish(outboxMessage, deliveryInfo)
    }
}
