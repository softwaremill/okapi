package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionContextValidator
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * Spring implementation of [TransactionContextValidator].
 *
 * Validates that the current thread is inside an active, non-read-only
 * Spring-managed transaction **on the specific [dataSource]** where
 * the outbox table lives.
 *
 * Uses [TransactionSynchronizationManager.getResource] to verify that
 * the outbox DataSource has an active connection bound in the current
 * transaction. This correctly handles multi-datasource setups:
 * a transaction on DataSource A will not satisfy validation when the
 * outbox lives on DataSource B.
 */
class SpringTransactionContextValidator(
    private val dataSource: DataSource,
) : TransactionContextValidator {
    override fun isInActiveReadWriteTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive() &&
        !TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
        TransactionSynchronizationManager.getResource(dataSource) != null
}
