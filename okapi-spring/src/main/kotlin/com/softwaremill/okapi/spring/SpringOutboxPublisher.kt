package com.softwaremill.okapi.spring

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Spring-aware wrapper around [OutboxPublisher] that validates transactional context
 * before delegating to the core publisher.
 *
 * Ensures the outbox pattern contract: publish must happen within an active read-write transaction.
 *
 * @throws IllegalStateException if no active read-write Spring transaction is present.
 */
class SpringOutboxPublisher(private val delegate: OutboxPublisher) {
    /**
     * Publishes a message within the currently active Spring transaction.
     * Fails fast if no read-write transaction is active.
     *
     * @throws IllegalStateException if no active read-write transaction is present.
     */
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId {
        checkActiveReadWriteTransaction()
        return delegate.publish(outboxMessage, deliveryInfo)
    }

    private fun checkActiveReadWriteTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Transaction is not active. Ensure that this operation is executed within an active transactional context."
        }
        check(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            "Transaction is read-only. Ensure that this operation is executed within a read-write transactional context."
        }
    }
}
