package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Duration.ofMillis
import java.time.Duration.ofSeconds
import java.time.Instant
import javax.sql.DataSource

class OutboxProcessorAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })

    test("processor bean is created by default") {
        contextRunner.run { ctx ->
            ctx.getBean(OutboxProcessorScheduler::class.java).shouldNotBeNull()
        }
    }

    test("processor bean is not created when disabled") {
        contextRunner
            .withPropertyValues("okapi.processor.enabled=false")
            .run { ctx ->
                ctx.containsBean("outboxProcessorScheduler") shouldBe false
            }
    }

    test("properties are bound from application config") {
        contextRunner
            .withPropertyValues(
                "okapi.processor.interval=500ms",
                "okapi.processor.batch-size=20",
                "okapi.processor.max-retries=3",
            )
            .run { ctx ->
                val props = ctx.getBean(OutboxProcessorProperties::class.java)
                props.interval shouldBe ofMillis(500)
                props.batchSize shouldBe 20
                props.maxRetries shouldBe 3
            }
    }

    test("default properties when nothing is configured") {
        contextRunner.run { ctx ->
            val props = ctx.getBean(OutboxProcessorProperties::class.java)
            props.interval shouldBe ofSeconds(1)
            props.batchSize shouldBe 10
            props.maxRetries shouldBe 5
        }
    }

    test("SmartLifecycle is running after context start") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxProcessorScheduler::class.java)
            scheduler.isRunning shouldBe true
        }
    }

    test("invalid batch-size triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.processor.batch-size=0")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("stop callback is always invoked") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxProcessorScheduler::class.java)
            var callbackInvoked = false
            scheduler.stop { callbackInvoked = true }
            callbackInvoked shouldBe true
        }
    }

    test("listener is wired into processor when MeterRegistry is present") {
        contextRunner
            .withBean(io.micrometer.core.instrument.MeterRegistry::class.java, {
                io.micrometer.core.instrument.simple.SimpleMeterRegistry()
            })
            .run { ctx ->
                ctx.getBean(MicrometerOutboxListener::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
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
