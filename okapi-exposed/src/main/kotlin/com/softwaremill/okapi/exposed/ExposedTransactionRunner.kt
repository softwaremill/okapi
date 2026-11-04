package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.TransactionRunner
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
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
 * On MySQL and MariaDB it runs at READ COMMITTED by default (issue #129). Under MySQL's default
 * REPEATABLE READ the claim's `FOR UPDATE SKIP LOCKED` takes gap locks at the end of each claimed
 * delivery type's range in the claim index -- where every `publish()` of that type inserts -- so publishers block until the
 * claiming transaction, and with it delivery, finishes. This overrides a
 * `DatabaseConfig.defaultIsolationLevel` set for the rest of the application. Other databases keep
 * their default (Postgres already defaults to READ COMMITTED).
 *
 * @param database The [Database] instance where the outbox table resides.
 */
class ExposedTransactionRunner private constructor(
    private val database: Database,
    explicitIsolation: Int?,
    isolationIsExplicit: Boolean,
) : TransactionRunner {
    constructor(database: Database) : this(database, null, isolationIsExplicit = false)

    /**
     * @param transactionIsolation a `java.sql.Connection.TRANSACTION_*` level, or `null` for the
     *   [database]'s default isolation -- on every database, MySQL included.
     */
    constructor(database: Database, transactionIsolation: Int?) : this(database, transactionIsolation, isolationIsExplicit = true)

    // Lazy: resolving the dialect may open a connection, which the constructor shouldn't do.
    private val transactionIsolation: Int? by lazy {
        when {
            isolationIsExplicit -> explicitIsolation
            database.dialect is MysqlDialect -> Connection.TRANSACTION_READ_COMMITTED // MariaDBDialect extends it
            else -> null
        }
    }

    override fun <T> runInTransaction(block: () -> T): T = transaction(database, transactionIsolation = transactionIsolation) { block() }
}
