package com.softwaremill.okapi.benchmarks.support

import com.mysql.cj.jdbc.MysqlDataSource
import com.softwaremill.okapi.core.ConnectionProvider
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.containers.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Brings up a real MySQL container, runs Liquibase migrations, and exposes a
 * [PersistentConnectionProvider] holding one physical connection reused across the whole JMH
 * trial — a raw per-invocation `dataSource.connection` (as [PostgresBenchmarkSupport] uses) pays
 * MySQL's connection-handshake cost on every invocation, which swamps the roundtrip savings this
 * benchmark exists to measure. One reused connection matches how okapi actually runs: a pooled
 * connection borrowed once per scheduler tick, not re-established per `processNext()` call.
 *
 * [rewriteBatchedStatements] toggles the Connector/J JDBC URL property — see README "Performance"
 * section: without it, `executeBatch()` sends one roundtrip per statement on MySQL.
 */
class MysqlBenchmarkSupport(private val rewriteBatchedStatements: Boolean) {
    private val container = MySQLContainer<Nothing>("mysql:8.0")
    lateinit var dataSource: DataSource
    lateinit var jdbc: PersistentConnectionProvider

    fun start() {
        container.start()
        dataSource = MysqlDataSource().apply {
            setURL("${container.jdbcUrl}?rewriteBatchedStatements=$rewriteBatchedStatements")
            user = container.username
            setPassword(container.password)
        }
        jdbc = PersistentConnectionProvider(dataSource.connection.apply { autoCommit = false })
        runLiquibase()
    }

    fun stop() {
        jdbc.close()
        container.stop()
    }

    fun truncate() {
        jdbc.withTransaction {
            jdbc.withConnection { conn ->
                conn.createStatement().use { it.execute("DELETE FROM okapi_outbox") }
            }
        }
    }

    private fun runLiquibase() {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/mysql/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
        connection.close()
    }
}

/** Holds one physical [Connection] for the benchmark's lifetime instead of opening one per call. */
class PersistentConnectionProvider(private val connection: Connection) : ConnectionProvider {
    override fun <T> withConnection(block: (Connection) -> T): T = block(connection)

    fun <T> withTransaction(block: () -> T): T {
        val result = block()
        connection.commit()
        return result
    }

    fun close() {
        connection.close()
    }
}
