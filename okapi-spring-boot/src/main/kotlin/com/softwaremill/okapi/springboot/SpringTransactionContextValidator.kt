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
 *
 * Note: [TransactionSynchronizationManager.isCurrentTransactionReadOnly] is a thread-global
 * flag, not scoped to a specific DataSource. In rare scenarios with concurrent transactions
 * on multiple DataSources with mixed read-only semantics (without REQUIRES_NEW suspension),
 * this flag may reflect the wrong transaction's read-only state. The [getResource][TransactionSynchronizationManager.getResource]
 * check is the primary DataSource-specific guard.
 */
internal class SpringTransactionContextValidator(
    private val dataSource: DataSource,
) : TransactionContextValidator {
    override fun isInActiveReadWriteTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive() &&
        !TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
        TransactionSynchronizationManager.getResource(dataSource) != null

    override val failureMessage: String
        get() = "No active read-write transaction on the outbox DataSource. " +
            "Ensure publish() is called within a @Transactional method that uses the same DataSource as the outbox table. " +
            "In multi-datasource setups, verify okapi.datasource-qualifier is set correctly."
}
