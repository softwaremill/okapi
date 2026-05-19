package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import javax.sql.DataSource

class DataSourceQualifierAutoConfigurationTest : FunSpec({

    fun baseRunner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })

    test("no qualifier set, single datasource — uses that datasource") {
        val ds = SimpleDriverDataSource()
        baseRunner()
            .withBean("myDs", DataSource::class.java, { ds })
            .run { ctx ->
                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                publisher.shouldNotBeNull()
            }
    }

    test("qualifier set to existing bean name — uses that datasource") {
        val primaryDs = SimpleDriverDataSource()
        val outboxDs = SimpleDriverDataSource()
        baseRunner()
            .withInitializer { context ->
                (context as GenericApplicationContext).registerBean<DataSource>("primaryDs", primary = true) { primaryDs }
            }
            .withBean("outboxDs", DataSource::class.java, { outboxDs })
            .withPropertyValues("okapi.datasource-qualifier=outboxDs")
            .run { ctx ->
                val resolved = OutboxAutoConfiguration.resolveDataSource(
                    ctx.getBeansOfType(DataSource::class.java),
                    primaryDs,
                    ctx.getBean(OkapiProperties::class.java),
                )
                resolved shouldBeSameInstanceAs outboxDs
            }
    }

    test("qualifier set to non-existent bean name — context fails with clear error") {
        baseRunner()
            .withBean("realDs", DataSource::class.java, { SimpleDriverDataSource() })
            .withPropertyValues("okapi.datasource-qualifier=missingDs")
            .run { ctx ->
                val failure = ctx.startupFailure
                failure.shouldNotBeNull()
                failure.message shouldContain "missingDs"
            }
    }

    test("no qualifier set, multiple datasources — uses primary datasource") {
        val primaryDs = SimpleDriverDataSource()
        val secondaryDs = SimpleDriverDataSource()
        baseRunner()
            .withInitializer { context ->
                (context as GenericApplicationContext).registerBean<DataSource>("primaryDs", primary = true) { primaryDs }
            }
            .withBean("secondaryDs", DataSource::class.java, { secondaryDs })
            .run { ctx ->
                val resolved = OutboxAutoConfiguration.resolveDataSource(
                    ctx.getBeansOfType(DataSource::class.java),
                    ctx.getBean(DataSource::class.java),
                    ctx.getBean(OkapiProperties::class.java),
                )
                resolved shouldBeSameInstanceAs primaryDs
            }
    }
})

private fun noOpTransactionRunner() = object : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}
