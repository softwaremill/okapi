package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.TransactionRunner
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Exposed implementation of [TransactionRunner].
 *
 * Wraps the block in Exposed's `transaction(database) { }`.
 * Used by the outbox scheduler/processor when running outside of
 * an existing transactional context (e.g., background processing thread).
 *
 * @param database The [Database] instance where the outbox table resides.
 */
class ExposedTransactionRunner(
    private val database: Database,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transaction(database) { block() }
}
