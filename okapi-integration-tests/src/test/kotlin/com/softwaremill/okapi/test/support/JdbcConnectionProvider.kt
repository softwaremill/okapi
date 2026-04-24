package com.softwaremill.okapi.test.support

import com.softwaremill.okapi.core.ConnectionProvider
import java.sql.Connection
import javax.sql.DataSource

/**
 * Test helper that provides a [ConnectionProvider] backed by a [ThreadLocal] connection.
 * Use [withTransaction] to bind a JDBC connection for the duration of a block;
 * that outer scope owns commit/rollback and close.
 */
class JdbcConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
    private val threadLocalConnection = ThreadLocal<Connection>()

    override fun <T> withConnection(block: (Connection) -> T): T {
        val connection = threadLocalConnection.get()
            ?: throw IllegalStateException("No connection bound to current thread. Use withTransaction { } in tests.")
        return block(connection)
    }

    fun <T> withTransaction(block: () -> T): T = withTransaction(transactionIsolation = null, block)

    fun <T> withTransaction(transactionIsolation: Int?, block: () -> T): T {
        val conn = dataSource.connection
        conn.autoCommit = false
        if (transactionIsolation != null) {
            conn.transactionIsolation = transactionIsolation
        }
        threadLocalConnection.set(conn)
        return try {
            val result = block()
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            threadLocalConnection.remove()
            conn.close()
        }
    }
}
