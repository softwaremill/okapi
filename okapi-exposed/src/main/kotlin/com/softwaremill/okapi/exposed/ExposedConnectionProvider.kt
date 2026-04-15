package com.softwaremill.okapi.exposed

import com.softwaremill.okapi.core.ConnectionProvider
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection

/**
 * Exposed implementation of [ConnectionProvider].
 *
 * Retrieves the JDBC [Connection] from the current Exposed transaction.
 * Use this when your application manages transactions via Exposed's
 * `transaction(database) { }` blocks (e.g., Ktor + Exposed apps).
 *
 * The returned connection is **borrowed** from Exposed's active transaction —
 * the caller must NOT close it.
 */
class ExposedConnectionProvider : ConnectionProvider {
    override fun getConnection(): Connection = TransactionManager.current().connection.connection as Connection
}
