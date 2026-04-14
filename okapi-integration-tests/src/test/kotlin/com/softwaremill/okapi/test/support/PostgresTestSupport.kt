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
            jdbc.getConnection().createStatement().use { it.execute("TRUNCATE TABLE outbox") }
        }
    }

    private fun runLiquibase() {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
        connection.close()
    }
}
