package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.OkapiLiquibaseAutoConfiguration
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.test.support.pgDataSourceOf
import com.softwaremill.okapi.test.support.runOkapiLiquibaseOn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Proves the JPA branch of `extractDataSource`: a `JpaTransactionManager` whose auto-detected
 * DataSource differs from okapi's outbox DataSource triggers `validatePtmDataSourceMatch`
 * fail-fast at startup. Companion: `WrongPtmMultiDataSourceFailFastTest` (non-extractable PTMs).
 */
class JpaTransactionManagerFailFastTest : FunSpec({

    val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var dsA: DataSource
    lateinit var dsB: DataSource

    beforeSpec {
        container.start()
        dsA = pgDataSourceOf(container)
        dsB = pgDataSourceOf(container)
        runOkapiLiquibaseOn(container)
    }

    afterSpec { container.stop() }

    test("JpaTransactionManager bound to a different DataSource than the outbox DS fails fast at startup") {
        val emf = LocalContainerEntityManagerFactoryBean().apply {
            dataSource = dsA
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            // Empty package scan creates an implicit persistence unit without requiring persistence.xml
            // — we have no @Entity classes; the EMF only needs to exist so JpaTransactionManager can
            // auto-detect its DataSource.
            setPackagesToScan()
            afterPropertiesSet()
        }.`object`.shouldNotBeNull()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OutboxAutoConfiguration::class.java,
                    OkapiLiquibaseAutoConfiguration::class.java,
                ),
            )
            .withBean("dsB", DataSource::class.java, { dsB }, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean("dsA", DataSource::class.java, { dsA })
            .withBean("jpaTmA", PlatformTransactionManager::class.java, { JpaTransactionManager(emf) })
            .withBean(MessageDeliverer::class.java, { JpaTestStubDeliverer })
            .withBean(PostgresOutboxStore::class.java, {
                PostgresOutboxStore(SpringConnectionProvider(dsB), java.time.Clock.systemUTC())
            })
            .withPropertyValues("okapi.liquibase.enabled=false")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                failure.stackTraceToString() shouldContain
                    "is bound to a different DataSource than okapi's outbox DataSource"
            }
    }
})

private object JpaTestStubDeliverer : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
