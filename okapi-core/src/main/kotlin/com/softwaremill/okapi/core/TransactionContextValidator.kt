package com.softwaremill.okapi.core

/**
 * Checks whether the current execution context is inside an active read-write transaction.
 *
 * Framework-specific modules provide implementations:
 * - `okapi-spring`: checks via `TransactionSynchronizationManager`
 * - `okapi-ktor`: checks via Exposed's `TransactionManager.currentOrNull()`
 * - Standalone: no-op (always returns true)
 */
interface TransactionContextValidator {
    fun isInActiveReadWriteTransaction(): Boolean
}
