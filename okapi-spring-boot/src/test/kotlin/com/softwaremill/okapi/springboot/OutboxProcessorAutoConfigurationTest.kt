package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Duration.ofMillis
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds
import java.time.Instant
import javax.sql.DataSource

class OutboxProcessorAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiMicrometerAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })
        // Liquibase migration is exercised end-to-end against real DBs; disable it here so slice
        // tests don't try to run okapi's Postgres changelog against a fake DataSource.
        .withPropertyValues("okapi.liquibase.enabled=false")

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

    test("listener, metrics and refresher are wired when a MeterRegistry bean is provided directly") {
        contextRunner
            .withBean(io.micrometer.core.instrument.MeterRegistry::class.java, {
                io.micrometer.core.instrument.simple.SimpleMeterRegistry()
            })
            .run { ctx ->
                ctx.getBean(MicrometerOutboxListener::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
                ctx.getBean(OutboxMetricsRefresher::class.java).shouldNotBeNull()
            }
    }

    test("metrics refresh-interval property is bound") {
        contextRunner
            .withBean(io.micrometer.core.instrument.MeterRegistry::class.java, {
                io.micrometer.core.instrument.simple.SimpleMeterRegistry()
            })
            .withPropertyValues("okapi.metrics.refresh-interval=1m")
            .run { ctx ->
                val props = ctx.getBean(OkapiMetricsProperties::class.java)
                props.refreshInterval shouldBe ofMinutes(1)
            }
    }

    test("metrics refresh-interval defaults to 15s") {
        contextRunner
            .withBean(io.micrometer.core.instrument.MeterRegistry::class.java, {
                io.micrometer.core.instrument.simple.SimpleMeterRegistry()
            })
            .run { ctx ->
                val props = ctx.getBean(OkapiMetricsProperties::class.java)
                props.refreshInterval shouldBe ofSeconds(15)
            }
    }

    // Exercises real Spring Boot metrics auto-config ordering: MeterRegistry is created by SimpleMetricsExportAutoConfiguration
    // (not pre-registered as a user bean), so @AutoConfigureAfter on OkapiMicrometerAutoConfiguration must actually resolve
    // and order correctly for the listener to be wired.
    test("listener is wired under real Spring Boot metrics auto-config ordering") {
        // Each pair lists the same auto-config in 3.5.x (`actuate.autoconfigure.metrics`) and 4.0.x (`micrometer.metrics.autoconfigure`) layouts.
        val metricsAutoConfig = resolveSpringBootClass(
            "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        )
        val compositeMeterRegistryAutoConfig = resolveSpringBootClass(
            "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        )
        val simpleMetricsExportAutoConfig = resolveSpringBootClass(
            "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
        )

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OutboxAutoConfiguration::class.java,
                    OkapiMicrometerAutoConfiguration::class.java,
                    metricsAutoConfig,
                    compositeMeterRegistryAutoConfig,
                    simpleMetricsExportAutoConfig,
                ),
            )
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            // Disable okapi's Liquibase so it doesn't try to run against the fake DataSource —
            // this test isolates the metrics auto-config ordering, not Liquibase.
            .withPropertyValues("okapi.liquibase.enabled=false")
            .run { ctx ->
                ctx.getBean(io.micrometer.core.instrument.MeterRegistry::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerOutboxListener::class.java).shouldNotBeNull()
                ctx.getBean(MicrometerOutboxMetrics::class.java).shouldNotBeNull()
                ctx.getBean(OutboxMetricsRefresher::class.java).shouldNotBeNull()
            }
    }

    // @AutoConfigureAfter(name = ...) silently drops entries whose class is missing — if none resolve, the ordering hint is a no-op.
    test("AutoConfigureAfter on OkapiMicrometerAutoConfiguration resolves on the runtime classpath") {
        val annotation = OkapiMicrometerAutoConfiguration::class.java.getAnnotation(AutoConfigureAfter::class.java)
        annotation.shouldNotBeNull()

        val declaredNames = annotation.name.toList()
        declaredNames.shouldNotBeEmpty()

        val classLoader = OkapiMicrometerAutoConfiguration::class.java.classLoader
        val resolvable = declaredNames.filter { name ->
            try {
                Class.forName(name, false, classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        withClue(
            "None of the @AutoConfigureAfter targets $declaredNames resolve on this Spring Boot runtime; " +
                "the ordering hint is silently ignored and OkapiMicrometerAutoConfiguration may be evaluated " +
                "before MeterRegistry is registered.",
        ) {
            resolvable.shouldNotBeEmpty()
        }
    }
})

// Loads a Spring Boot auto-config class by trying version-specific FQCNs in order.
// Lets a single test exercise both the 3.5.x (`...actuate.autoconfigure.metrics...`) and 4.0.x (`...micrometer.metrics.autoconfigure...`) layouts.
private fun resolveSpringBootClass(vararg candidateFqcns: String): Class<*> {
    val classLoader = OkapiMicrometerAutoConfiguration::class.java.classLoader
    return candidateFqcns.firstNotNullOfOrNull { fqcn ->
        try {
            Class.forName(fqcn, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }
    } ?: error("None of $candidateFqcns resolves on this Spring Boot runtime; check spring-boot-starter-actuator on the test classpath.")
}

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
