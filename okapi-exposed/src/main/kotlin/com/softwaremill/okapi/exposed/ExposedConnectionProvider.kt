package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.ConnectionProvider
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection

/**
 * Exposed implementation of [ConnectionProvider].
 *
 * Reads the JDBC [Connection] from Exposed's active `TransactionManager.current()` and
 * passes it to the caller's block. Exposed owns the connection's lifecycle — it commits
 * or rolls back, and returns the connection to the pool when the enclosing
 * `transaction(database) { }` block completes — so this provider performs no cleanup.
 *
 * Use when your application manages transactions via Exposed (e.g. Ktor + Exposed apps).
 * Must be called from within an active Exposed transaction; otherwise
 * `TransactionManager.current()` throws.
 */
class ExposedConnectionProvider : ConnectionProvider {
    override fun <T> withConnection(block: (Connection) -> T): T {
        val connection = TransactionManager.current().connection.connection as Connection
        return block(connection)
    }
}
