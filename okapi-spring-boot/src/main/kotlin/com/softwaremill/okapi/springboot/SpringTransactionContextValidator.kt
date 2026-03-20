package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionContextValidator
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Spring implementation of [TransactionContextValidator].
 *
 * Checks via [TransactionSynchronizationManager] whether the current thread
 * is inside an active, non-read-only Spring-managed transaction.
 */
class SpringTransactionContextValidator : TransactionContextValidator {
    override fun isInActiveReadWriteTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive() &&
        !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
}
