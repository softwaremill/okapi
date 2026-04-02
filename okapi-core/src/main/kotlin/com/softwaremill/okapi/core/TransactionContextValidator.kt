package com.softwaremill.okapi.core

/**
 * Checks whether the current execution context is inside an active read-write transaction.
 *
 * Framework-specific modules provide implementations:
 * - `okapi-spring`: [SpringTransactionContextValidator][com.softwaremill.okapi.springboot.SpringTransactionContextValidator]
 *   — checks via `TransactionSynchronizationManager`
 * - `okapi-core`: [ExposedTransactionContextValidator] — checks via Exposed's `TransactionManager.currentOrNull()`
 * - Standalone: no-op (always returns true)
 */
interface TransactionContextValidator {
    fun isInActiveReadWriteTransaction(): Boolean

    val failureMessage: String
        get() = "No active read-write transaction. Ensure that publish() is called within a transactional context."
}
