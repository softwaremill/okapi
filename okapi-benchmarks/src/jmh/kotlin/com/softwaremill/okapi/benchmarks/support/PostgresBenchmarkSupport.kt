package com.softwaremill.okapi.benchmarks.support

import com.softwaremill.okapi.core.ConnectionProvider
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Brings up a real Postgres container, runs Liquibase migrations,
 * and exposes a [ConnectionProvider] backed by a thread-local connection
 * (matching the integration-tests pattern so okapi-core stays unchanged).
 */
class PostgresBenchmarkSupport {
    private val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var dataSource: DataSource
    lateinit var jdbc: BenchmarkConnectionProvider

    fun start() {
        container.start()
        dataSource = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
        }
        jdbc = BenchmarkConnectionProvider(dataSource)
        runLiquibase()
    }

    fun stop() {
        container.stop()
    }

    fun truncate() {
        jdbc.withTransaction {
            jdbc.withConnection { conn ->
                conn.createStatement().use { it.execute("TRUNCATE TABLE outbox") }
            }
        }
    }

    private fun runLiquibase() {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
        connection.close()
    }
}

class BenchmarkConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
    private val threadLocalConnection = ThreadLocal<Connection>()

    override fun <T> withConnection(block: (Connection) -> T): T {
        val connection = threadLocalConnection.get()
            ?: error("No connection bound to current thread. Use withTransaction { } in benchmarks.")
        return block(connection)
    }

    fun <T> withTransaction(block: () -> T): T {
        val conn = dataSource.connection
        conn.autoCommit = false
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
