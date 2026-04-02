package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Instant
import javax.sql.DataSource

class DataSourceQualifierAutoConfigurationTest : FunSpec({

    fun baseRunner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })

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
            .withBean("primaryDs", DataSource::class.java, { primaryDs })
            .withBean("outboxDs", DataSource::class.java, { outboxDs })
            .withPropertyValues("okapi.datasource-qualifier=outboxDs")
            .run { ctx ->
                val resolved = OutboxAutoConfiguration.resolveDataSource(
                    ctx.getBeansOfType(DataSource::class.java),
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

    test("no qualifier set, multiple datasources — uses first (primary) datasource") {
        baseRunner()
            .withBean("ds1", DataSource::class.java, { SimpleDriverDataSource() })
            .withBean("ds2", DataSource::class.java, { SimpleDriverDataSource() })
            .run { ctx ->
                val publisher = ctx.getBean(SpringOutboxPublisher::class.java)
                publisher.shouldNotBeNull()
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
