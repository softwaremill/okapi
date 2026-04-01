package com.softwaremill.okapi.core

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager

/**
 * Exposed implementation of [TransactionContextValidator].
 *
 * Validates that the current thread is inside an active, non-read-only
 * Exposed transaction on the specified [database] instance (the one
 * where the outbox table lives).
 *
 * Uses the per-database variant of `currentOrNull()` which searches the
 * thread-local transaction stack filtered by [Database] instance.
 * This correctly handles multiple databases and nested transactions.
 *
 * @param database The [Database] instance where the outbox table resides.
 */
class ExposedTransactionContextValidator(
    private val database: Database,
) : TransactionContextValidator {
    override fun isInActiveReadWriteTransaction(): Boolean {
        val transaction = database.transactionManager.currentOrNull() ?: return false
        return !transaction.readOnly
    }
}
