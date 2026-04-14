package com.softwaremill.okapi.test.support

import com.mysql.cj.jdbc.MysqlDataSource
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.containers.MySQLContainer
import java.sql.DriverManager
import javax.sql.DataSource

class MysqlTestSupport {
    val container = MySQLContainer<Nothing>("mysql:8.0")
    lateinit var dataSource: DataSource
    lateinit var jdbc: JdbcConnectionProvider

    fun start() {
        container.start()
        dataSource = MysqlDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            setPassword(container.password)
        }
        jdbc = JdbcConnectionProvider(dataSource)
        runLiquibase()
    }

    fun stop() {
        container.stop()
    }

    fun truncate() {
        jdbc.withTransaction {
            jdbc.getConnection().createStatement().use { it.execute("DELETE FROM outbox") }
        }
    }

    private fun runLiquibase() {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/mysql/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
        connection.close()
    }
}
