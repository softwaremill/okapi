package com.softwaremill.okapi.test.support

import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import javax.sql.DataSource

class PostgresTestSupport {
    val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var dataSource: DataSource
    lateinit var jdbc: JdbcConnectionProvider

    fun start() {
        container.start()
        dataSource = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
        }
        jdbc = JdbcConnectionProvider(dataSource)
        runLiquibase()
    }

    fun stop() {
        container.stop()
    }

    fun truncate() {
        jdbc.withTransaction {
            jdbc.withConnection { conn ->
                conn.createStatement().use { it.execute("TRUNCATE TABLE okapi_outbox") }
            }
        }
    }

    private fun runLiquibase() = runOkapiLiquibaseOn(container)
}

/**
 * Applies okapi's PostgreSQL Liquibase changelog to the given container. For tests that manage
 * their own PostgreSQL containers (e.g. 2-DataSource setups) and can't use the single-container
 * `PostgresTestSupport` class.
 */
fun runOkapiLiquibaseOn(container: PostgreSQLContainer<Nothing>) {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn))
        Liquibase("com/softwaremill/okapi/db/postgres/changelog.xml", ClassLoaderResourceAccessor(), db).use {
            it.update("")
        }
    }
}

/** Builds a plain `PGSimpleDataSource` pointing at the given container. */
fun pgDataSourceOf(container: PostgreSQLContainer<Nothing>): DataSource = PGSimpleDataSource().apply {
    setURL(container.jdbcUrl)
    user = container.username
    password = container.password
}
