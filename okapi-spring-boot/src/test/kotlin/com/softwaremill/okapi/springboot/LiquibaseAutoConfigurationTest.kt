package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Instant
import javax.sql.DataSource

/**
 * Verifies that okapi configures dedicated Liquibase tracking tables (issue #37) so its migration
 * history stays isolated from the host application's `databasechangelog`.
 *
 * The bean-wiring contexts instantiate the inner @Configuration classes directly to avoid
 * `afterPropertiesSet()` (which would try to run Liquibase against a fake DataSource).
 *
 * The standalone property-binding test pins down the YAML contract — the keys
 * `okapi.liquibase.changelog-table` / `okapi.liquibase.changelog-lock-table` and their mapping
 * to the nested [OkapiProperties.Liquibase] data class. Without it, a refactor renaming the
 * Kotlin fields would silently break user configuration.
 */
class LiquibaseAutoConfigurationTest : FunSpec({

    val dataSource: DataSource = SimpleDriverDataSource()
    val dataSources = mapOf("primary" to dataSource)

    fun postgresConfig(props: OkapiProperties = OkapiProperties()) =
        OutboxAutoConfiguration.PostgresStoreConfiguration(dataSources, dataSource, props)

    fun mysqlConfig(props: OkapiProperties = OkapiProperties()) =
        OutboxAutoConfiguration.MysqlStoreConfiguration(dataSources, dataSource, props)

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })

    context("postgres liquibase") {
        test("uses dedicated changelog tables by default") {
            val liquibase = postgresConfig().okapiPostgresLiquibase()

            liquibase.databaseChangeLogTable shouldBe "okapi_databasechangelog"
            liquibase.databaseChangeLogLockTable shouldBe "okapi_databasechangeloglock"
        }

        test("honours custom changelog table names") {
            val props = OkapiProperties(
                liquibase = OkapiProperties.Liquibase(
                    changelogTable = "custom_changelog",
                    changelogLockTable = "custom_changelog_lock",
                ),
            )

            val liquibase = postgresConfig(props).okapiPostgresLiquibase()

            liquibase.databaseChangeLogTable shouldBe "custom_changelog"
            liquibase.databaseChangeLogLockTable shouldBe "custom_changelog_lock"
        }
    }

    context("mysql liquibase") {
        test("uses dedicated changelog tables by default") {
            val liquibase = mysqlConfig().okapiMysqlLiquibase()

            liquibase.databaseChangeLogTable shouldBe "okapi_databasechangelog"
            liquibase.databaseChangeLogLockTable shouldBe "okapi_databasechangeloglock"
        }

        test("honours custom changelog table names") {
            val props = OkapiProperties(
                liquibase = OkapiProperties.Liquibase(
                    changelogTable = "shared_changelog",
                    changelogLockTable = "shared_changelog_lock",
                ),
            )

            val liquibase = mysqlConfig(props).okapiMysqlLiquibase()

            liquibase.databaseChangeLogTable shouldBe "shared_changelog"
            liquibase.databaseChangeLogLockTable shouldBe "shared_changelog_lock"
        }
    }

    context("validation rejects blank table names") {
        data class BlankCase(
            val label: String,
            val build: () -> OkapiProperties.Liquibase,
            val expectedMessage: String,
        )

        val tableMsg = "okapi.liquibase.changelog-table must not be blank."
        val lockMsg = "okapi.liquibase.changelog-lock-table must not be blank."

        listOf(
            BlankCase("changelog-table — empty", { OkapiProperties.Liquibase(changelogTable = "") }, tableMsg),
            BlankCase("changelog-table — whitespace", { OkapiProperties.Liquibase(changelogTable = "   ") }, tableMsg),
            BlankCase("changelog-lock-table — empty", { OkapiProperties.Liquibase(changelogLockTable = "") }, lockMsg),
            BlankCase("changelog-lock-table — whitespace", { OkapiProperties.Liquibase(changelogLockTable = "   ") }, lockMsg),
        ).forEach { case ->
            test(case.label) {
                val ex = shouldThrow<IllegalArgumentException> { case.build() }
                ex.message shouldBe case.expectedMessage
            }
        }
    }

    test("okapi.liquibase.* properties bind to nested config") {
        contextRunner
            .withPropertyValues(
                "okapi.liquibase.changelog-table=app_changelog",
                "okapi.liquibase.changelog-lock-table=app_changelog_lock",
            )
            .run { ctx ->
                val props = ctx.getBean(OkapiProperties::class.java)
                props.liquibase.changelogTable shouldBe "app_changelog"
                props.liquibase.changelogLockTable shouldBe "app_changelog_lock"
            }
    }

    test("blank changelog-table property triggers startup failure") {
        // Pins that init { require(isNotBlank()) } actually propagates through Spring's
        // Binder — without this, a future refactor of OkapiProperties.Liquibase that bypasses
        // the constructor could silently let blank table names through.
        contextRunner
            .withPropertyValues("okapi.liquibase.changelog-table= ")
            .run { ctx ->
                val rootCause = generateSequence(ctx.startupFailure) { it.cause }.last()
                rootCause.message shouldBe "okapi.liquibase.changelog-table must not be blank."
            }
    }
})

private fun stubStore() = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

private fun stubDeliverer() = object : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
