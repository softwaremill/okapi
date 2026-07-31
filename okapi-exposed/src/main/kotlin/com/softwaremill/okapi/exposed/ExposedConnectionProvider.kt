package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.ConnectionProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection

/**
 * Exposed implementation of [ConnectionProvider].
 *
 * Reads the JDBC [Connection] from the active Exposed transaction **on [database]** (via the
 * per-database `database.transactionManager.currentOrNull()`) and passes it to the caller's
 * block. Exposed owns the connection's lifecycle — it commits or rolls back, and returns the
 * connection to the pool when the enclosing `transaction(database) { }` block completes — so
 * this provider performs no cleanup.
 *
 * Use when your application manages transactions via Exposed (e.g. Ktor + Exposed apps). In a
 * multi-database app, construct one instance per [Database] — same as [ExposedTransactionRunner]
 * and [ExposedTransactionContextValidator]. Scoping to a specific database (issue #96) matters
 * because every [com.softwaremill.okapi.core.OutboxStore] operation (`persist`, `claimPending`,
 * `updateAfterProcessing`, ...) goes through [withConnection]: without this, a global
 * `TransactionManager.currentOrNull()` would silently return whichever transaction happens to be
 * innermost-active on the calling thread — potentially the *wrong* database's connection in a
 * nested-transaction, multi-database app, with no error.
 *
 * Must be called from within an active Exposed transaction on [database]; otherwise
 * [withConnection] throws an [IllegalStateException] pointing the caller at the missing
 * `transaction(database) { }` block, instead of letting Exposed's own less specific error surface.
 *
 * @param database The [Database] instance where the outbox table resides.
 */
class ExposedConnectionProvider(private val database: Database) : ConnectionProvider {
    override fun <T> withConnection(block: (Connection) -> T): T {
        val transaction = database.transactionManager.currentOrNull()
            ?: throw IllegalStateException(
                "ExposedConnectionProvider.withConnection must be called within an Exposed " +
                    "transaction(database) { } block using this specific Database instance " +
                    "($database) -- the one where the outbox table lives.",
            )
        return block(transaction.connection.connection as Connection)
    }
}
