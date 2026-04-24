package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.TransactionalOutboxPublisher
import javax.sql.DataSource

/**
 * Spring-aware wrapper around [OutboxPublisher] that validates transactional context
 * before delegating to the core publisher.
 *
 * Ensures the outbox pattern contract: publish must happen within an active read-write transaction.
 *
 * @param delegate the core publisher to delegate to after validation
 * @param dataSource the DataSource where the outbox table lives — validation checks
 *        that the current Spring transaction is bound to this specific DataSource
 * @throws IllegalStateException if no active read-write Spring transaction is present.
 */
class SpringOutboxPublisher(delegate: OutboxPublisher, dataSource: DataSource) {
    private val transactionalPublisher = TransactionalOutboxPublisher(
        delegate = delegate,
        validator = SpringTransactionContextValidator(dataSource),
    )

    /**
     * Publishes a message within the currently active Spring transaction.
     * Fails fast if no read-write transaction is active.
     *
     * @throws IllegalStateException if no active read-write transaction is present.
     */
    @JvmName("publish")
    fun publish(outboxMessage: OutboxMessage, deliveryInfo: DeliveryInfo): OutboxId =
        transactionalPublisher.publish(outboxMessage, deliveryInfo)
}
