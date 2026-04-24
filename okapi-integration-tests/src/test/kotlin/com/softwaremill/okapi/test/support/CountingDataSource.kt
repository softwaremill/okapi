package com.softwaremill.okapi.test.support

import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Counting [DataSource] wrapper used to assert that every physical connection borrowed
 * from the pool is released. Both [opened] and [closed] increment only after the wrapped
 * delegate operation succeeds — if either `delegate.connection` or `delegate.close()`
 * throws, the counters stay consistent and the leak-proof assertion reflects reality.
 * On a failure to wrap a freshly opened connection, the delegate is closed before
 * rethrowing so no physical connection escapes.
 */
class CountingDataSource(val delegate: DataSource) : DataSource by delegate {
    val opened = AtomicInteger(0)
    val closed = AtomicInteger(0)

    override fun getConnection(): Connection {
        val connection = delegate.connection
        try {
            val wrapped = CountingConnection(connection, closed)
            opened.incrementAndGet()
            return wrapped
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
    }

    private class CountingConnection(
        private val delegate: Connection,
        private val closed: AtomicInteger,
    ) : Connection by delegate {
        override fun close() {
            delegate.close()
            closed.incrementAndGet()
        }
    }
}
