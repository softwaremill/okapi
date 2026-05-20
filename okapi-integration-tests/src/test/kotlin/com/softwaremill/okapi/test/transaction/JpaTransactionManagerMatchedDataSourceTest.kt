package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.springboot.SpringTransactionRunner
import com.softwaremill.okapi.test.support.pgDataSourceOf
import com.softwaremill.okapi.test.support.runOkapiLiquibaseOn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Companion to `JpaTransactionManagerFailFastTest`: proves the MATCH branch of the JPA path.
 * When the `JpaTransactionManager`'s auto-detected DataSource equals okapi's outbox DataSource,
 * `validatePtmDataSourceMatch` succeeds and `okapiTransactionRunner` is wired to that PTM.
 */
class JpaTransactionManagerMatchedDataSourceTest : FunSpec({

    val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var ds: DataSource

    beforeSpec {
        container.start()
        ds = pgDataSourceOf(container)
        runOkapiLiquibaseOn(container)
    }

    afterSpec { container.stop() }

    test("JpaTransactionManager bound to the SAME DataSource as the outbox: context starts cleanly") {
        val emf = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = ds
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setPackagesToScan()
            afterPropertiesSet()
        }.`object`.shouldNotBeNull()

        val jpaPtm = JpaTransactionManager(emf)

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OutboxAutoConfiguration::class.java,
                    OkapiLiquibaseAutoConfiguration::class.java,
                ),
            )
            .withBean(DataSource::class.java, { ds })
            .withBean("jpaTm", PlatformTransactionManager::class.java, { jpaPtm })
            .withBean(MessageDeliverer::class.java, { JpaMatchStubDeliverer })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(ds), java.time.Clock.systemUTC())
            })
            .withPropertyValues("okapi.liquibase.enabled=false")
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
            }
    }
})

private object JpaMatchStubDeliverer : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
