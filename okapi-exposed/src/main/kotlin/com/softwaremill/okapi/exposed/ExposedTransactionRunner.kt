package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.TransactionRunner
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/**
 * Exposed implementation of [TransactionRunner].
 *
 * Wraps the block in Exposed's `transaction(database) { }`.
 * Used by the outbox scheduler/processor when running outside of
 * an existing transactional context (e.g., background processing thread).
 *
 * Runs at [transactionIsolation], READ COMMITTED by default (issue #129). Under MySQL's default
 * REPEATABLE READ the claim's `FOR UPDATE SKIP LOCKED` takes gap locks at the end of the
 * `(status, created_at)` index -- where every `publish()` inserts -- so publishers block until the
 * claiming transaction, and with it delivery, finishes. On Postgres READ COMMITTED is already the
 * default. Pass `null` to use the [database]'s default isolation instead.
 *
 * @param database The [Database] instance where the outbox table resides.
 * @param transactionIsolation a `java.sql.Connection.TRANSACTION_*` level, or `null` for the database default.
 */
class ExposedTransactionRunner @JvmOverloads constructor(
    private val database: Database,
    private val transactionIsolation: Int? = Connection.TRANSACTION_READ_COMMITTED,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transaction(database, transactionIsolation = transactionIsolation) { block() }
}
