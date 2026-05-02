package com.softwaremill.okapi.springboot

import com.mysql.cj.jdbc.MysqlDataSource
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * End-to-end check that [OutboxAutoConfiguration] runs okapi's Liquibase migrations against real
 * databases and writes its history into the dedicated tracking tables (issue #37).
 *
 * Unit tests in [LiquibaseAutoConfigurationTest] verify bean wiring and YAML property binding;
 * this test proves the setters actually flow through SpringLiquibase to real DDL — i.e. that
 * `okapi_databasechangelog` exists after startup and the host application's `databasechangelog`
 * stays untouched. Postgres and MySQL get equal coverage because they use different Liquibase
 * adapters and different DDL semantics.
 */
class LiquibaseE2ETest : FunSpec({

    val postgres = PostgreSQLContainer<Nothing>("postgres:16")
    val mysql = MySQLContainer<Nothing>("mysql:8.0")

    beforeSpec {
        postgres.start()
        mysql.start()
    }
    afterSpec {
        postgres.stop()
        mysql.stop()
    }

    context("postgres") {
        fun dataSource(): DataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        fun resetSchema() {
            dataSource().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP SCHEMA public CASCADE")
                    stmt.execute("CREATE SCHEMA public")
                }
            }
        }

        fun listTables(ds: DataSource): Set<String> = ds.connection.use { conn ->
            conn.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
            }
        }

        fun runner(ds: DataSource) = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { ds })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.purger.enabled=false",
            )

        beforeEach { resetSchema() }

        test("autoconfig creates okapi_databasechangelog and runs okapi migrations") {
            val ds = dataSource()

            runner(ds).run { ctx ->
                ctx.startupFailure.shouldBeNull()

                val tables = listTables(ds)
                tables shouldContain "okapi_databasechangelog"
                tables shouldContain "okapi_databasechangeloglock"
                tables shouldContain "okapi_outbox"
                tables shouldNotContain "outbox"
                tables shouldNotContain "databasechangelog"
                tables shouldNotContain "databasechangeloglock"
            }
        }

        test("custom changelog-table property creates the named table instead") {
            val ds = dataSource()

            runner(ds)
                .withPropertyValues(
                    "okapi.liquibase.changelog-table=my_outbox_changelog",
                    "okapi.liquibase.changelog-lock-table=my_outbox_changelog_lock",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val tables = listTables(ds)
                    tables shouldContain "my_outbox_changelog"
                    tables shouldContain "my_outbox_changelog_lock"
                    tables shouldContain "okapi_outbox"
                    tables shouldNotContain "okapi_databasechangelog"
                }
        }
    }

    context("mysql") {
        fun dataSource(): DataSource = MysqlDataSource().apply {
            setURL(mysql.jdbcUrl)
            user = mysql.username
            setPassword(mysql.password)
        }

        fun resetSchema() {
            dataSource().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0")
                    val tables = mutableListOf<String>()
                    conn.metaData.getTables(mysql.databaseName, null, "%", arrayOf("TABLE")).use { rs ->
                        while (rs.next()) tables.add(rs.getString("TABLE_NAME"))
                    }
                    tables.forEach { stmt.execute("DROP TABLE IF EXISTS `$it`") }
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }

        fun listTables(ds: DataSource): Set<String> = ds.connection.use { conn ->
            conn.metaData.getTables(mysql.databaseName, null, "%", arrayOf("TABLE")).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
            }
        }

        // Hide PostgresOutboxStore from the classpath: both `okapi-postgres` and `okapi-mysql` are
        // on the test classpath, and PostgresStoreConfiguration would otherwise activate first and
        // try to run Postgres-specific Liquibase changesets against this MySQL container.
        fun runner(ds: DataSource) = ApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(PostgresOutboxStore::class.java))
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { ds })
            .withPropertyValues(
                "okapi.processor.enabled=false",
                "okapi.purger.enabled=false",
            )

        beforeEach { resetSchema() }

        test("autoconfig creates okapi_databasechangelog and runs okapi migrations") {
            val ds = dataSource()

            runner(ds).run { ctx ->
                ctx.startupFailure.shouldBeNull()

                val tables = listTables(ds)
                tables shouldContain "okapi_databasechangelog"
                tables shouldContain "okapi_databasechangeloglock"
                tables shouldContain "okapi_outbox"
                tables shouldNotContain "outbox"
                tables shouldNotContain "databasechangelog"
                tables shouldNotContain "databasechangeloglock"
            }
        }

        test("custom changelog-table property creates the named table instead") {
            val ds = dataSource()

            runner(ds)
                .withPropertyValues(
                    "okapi.liquibase.changelog-table=my_outbox_changelog",
                    "okapi.liquibase.changelog-lock-table=my_outbox_changelog_lock",
                )
                .run { ctx ->
                    ctx.startupFailure.shouldBeNull()

                    val tables = listTables(ds)
                    tables shouldContain "my_outbox_changelog"
                    tables shouldContain "my_outbox_changelog_lock"
                    tables shouldContain "okapi_outbox"
                    tables shouldNotContain "okapi_databasechangelog"
                }
        }
    }
})

private fun stubDeliverer() = object : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
