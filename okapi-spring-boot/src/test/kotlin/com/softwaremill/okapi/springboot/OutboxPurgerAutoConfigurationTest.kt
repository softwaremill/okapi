package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxPurgerConfig
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Duration.ofDays
import java.time.Duration.ofHours
import java.time.Duration.ofMinutes
import java.time.Instant
import javax.sql.DataSource

class OutboxPurgerAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })

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
                "okapi.purger.retention=14d",
                "okapi.purger.interval=30m",
                "okapi.purger.batch-size=200",
            )
            .run { ctx ->
                val props = ctx.getBean(OutboxPurgerProperties::class.java)
                props.retention shouldBe ofDays(14)
                props.interval shouldBe ofMinutes(30)
                props.batchSize shouldBe 200
            }
    }

    test("default properties when nothing is configured") {
        contextRunner.run { ctx ->
            val props = ctx.getBean(OutboxPurgerProperties::class.java)
            props.retention shouldBe ofDays(7)
            props.interval shouldBe ofHours(1)
            props.batchSize shouldBe 100
        }
    }

    test("SmartLifecycle is running after context start") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxPurgerScheduler::class.java)
            scheduler.isRunning shouldBe true
        }
    }

    test("invalid retention triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.purger.retention=0s")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("stop callback is always invoked") {
        val scheduler = OutboxPurgerScheduler(
            outboxStore = stubStore(),
            config = OutboxPurgerConfig(interval = ofMinutes(60)),
        )
        scheduler.start()

        var callbackInvoked = false
        scheduler.stop { callbackInvoked = true }

        callbackInvoked shouldBe true
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
