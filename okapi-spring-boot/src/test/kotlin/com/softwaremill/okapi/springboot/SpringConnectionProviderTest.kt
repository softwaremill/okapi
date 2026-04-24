package com.softwaremill.okapi.springboot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.h2.jdbcx.JdbcDataSource
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class SpringConnectionProviderTest : FunSpec({

    val h2 = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:okapi-spring-conn-provider;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
    }

    afterEach {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    test("releases a pool-borrowed connection when called outside a Spring transaction") {
        val counter = CountingDataSource(h2)
        val provider = SpringConnectionProvider(counter)

        provider.withConnection { /* no-op */ }

        counter.opened.get() shouldBe 1
        counter.closed.get() shouldBe 1
    }

    test("never leaks when called repeatedly outside a Spring transaction") {
        val counter = CountingDataSource(h2)
        val provider = SpringConnectionProvider(counter)

        repeat(50) { provider.withConnection { /* no-op */ } }

        counter.opened.get() shouldBe 50
        counter.closed.get() shouldBe 50
    }

    test("still releases the connection when the block throws") {
        val counter = CountingDataSource(h2)
        val provider = SpringConnectionProvider(counter)

        runCatching { provider.withConnection<Unit> { error("boom") } }

        counter.opened.get() shouldBe 1
        counter.closed.get() shouldBe 1
    }

    test("reuses the transaction-bound connection and does not close it") {
        val counter = CountingDataSource(h2)
        val bound = counter.connection
        val openedBefore = counter.opened.get()
        val closedBefore = counter.closed.get()

        val holder = ConnectionHolder(bound).apply { requested() }
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.bindResource(counter, holder)
        try {
            val provider = SpringConnectionProvider(counter)
            var passed: Connection? = null
            provider.withConnection { passed = it }

            passed shouldBe bound
            counter.opened.get() shouldBe openedBefore
            counter.closed.get() shouldBe closedBefore
            bound.isClosed shouldBe false
        } finally {
            TransactionSynchronizationManager.unbindResource(counter)
            bound.close()
        }
    }
})

private class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
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
