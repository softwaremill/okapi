package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

class OutboxPurgerAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })

    test("purger bean is created by default") {
        contextRunner.run { ctx ->
            ctx.getBean(OutboxPurgerScheduler::class.java).shouldNotBeNull()
        }
    }

    test("purger bean is not created when disabled") {
        contextRunner
            .withPropertyValues("okapi.purger.enabled=false")
            .run { ctx ->
                ctx.containsBean("outboxPurgerScheduler") shouldBe false
            }
    }

    test("properties are bound from application config") {
        contextRunner
            .withPropertyValues(
                "okapi.purger.retention-days=14",
                "okapi.purger.interval-minutes=30",
                "okapi.purger.batch-size=200",
            )
            .run { ctx ->
                val props = ctx.getBean(OkapiPurgerProperties::class.java)
                props.retentionDays shouldBe 14
                props.intervalMinutes shouldBe 30
                props.batchSize shouldBe 200
            }
    }

    test("default properties when nothing is configured") {
        contextRunner.run { ctx ->
            val props = ctx.getBean(OkapiPurgerProperties::class.java)
            props.retentionDays shouldBe 7
            props.intervalMinutes shouldBe 60
            props.batchSize shouldBe 100
        }
    }

    test("SmartLifecycle is running after context start") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxPurgerScheduler::class.java)
            scheduler.isRunning shouldBe true
        }
    }

    test("invalid retention-days triggers constructor validation") {
        contextRunner
            .withPropertyValues("okapi.purger.retention-days=0")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
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
