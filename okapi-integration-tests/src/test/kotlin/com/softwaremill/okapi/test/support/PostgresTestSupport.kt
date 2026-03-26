package com.softwaremill.okapi.test.support

import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

class PostgresTestSupport {
    val container = PostgreSQLContainer<Nothing>("postgres:16")

    fun start() {
        container.start()
        Database.connect(
            url = container.jdbcUrl,
            driver = container.driverClassName,
            user = container.username,
            password = container.password,
        )
        runLiquibase()
    }

    fun stop() {
        container.stop()
    }

    fun truncate() {
        transaction { exec("TRUNCATE TABLE outbox") }
    }

    private fun runLiquibase() {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
        connection.close()
    }
}
