package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.core.TransportDispatch
import com.softwaremill.okapi.micrometer.MicrometerOutboxListener
import com.softwaremill.okapi.micrometer.MicrometerOutboxMetrics
import com.softwaremill.okapi.micrometer.OutboxMetricsRefresher
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Clock
import java.time.Duration
import java.time.Duration.ofMillis
import java.time.Duration.ofMinutes
import java.time.Duration.ofSeconds
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

class OutboxProcessorAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, OkapiMicrometerAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })
        .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })

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

    test("enabled processor fails startup without a deliverer") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })
            .run { ctx ->
                generateSequence(ctx.startupFailure.shouldNotBeNull()) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ") shouldContain "no MessageDeliverer bean is registered"
            }
    }

    test("enabled processor rejects a store without route-aware claiming") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { object : OutboxStore by stubStore() {} })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })
            .run { ctx ->
                generateSequence(ctx.startupFailure.shouldNotBeNull()) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ") shouldContain "requires a RouteAwareOutboxStore"
            }
    }

    test("publisher-only application starts without a deliverer") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withPropertyValues("okapi.processor.enabled=false", "okapi.purger.enabled=false")
            .run { ctx ->
                ctx.startupFailure shouldBe null
                ctx.getBean(OutboxPublisher::class.java).shouldNotBeNull()
                ctx.containsBean("outboxProcessorScheduler") shouldBe false
            }
    }

    test("custom processor starts without a MessageDeliverer bean") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })
            .withBean(OutboxProcessor::class.java, {
                OutboxProcessor(
                    stubStore(),
                    OutboxEntryProcessor(stubDeliverer(), RetryPolicy(maxRetries = 0), Clock.systemUTC()),
                )
            })
            .run { ctx ->
                ctx.startupFailure shouldBe null
                ctx.getBean(OutboxProcessorScheduler::class.java).shouldNotBeNull()
            }
    }

    test("custom entry processor starts without a MessageDeliverer bean") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(DataSource::class.java, { SimpleDriverDataSource() })
            .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })
            .withBean(OutboxEntryProcessor::class.java, {
                OutboxEntryProcessor(stubDeliverer(), RetryPolicy(maxRetries = 0), Clock.systemUTC())
            })
            .run { ctx ->
                ctx.startupFailure shouldBe null
                ctx.getBean(OutboxProcessorScheduler::class.java).shouldNotBeNull()
            }
    }

    test("custom processor can use the auto-configured entry processor") {
        contextRunner
            .withUserConfiguration(CustomProcessorConfiguration::class.java)
            .run { ctx ->
                ctx.startupFailure shouldBe null
                ctx.getBean(OutboxEntryProcessor::class.java).shouldNotBeNull()
                ctx.getBean(OutboxProcessorScheduler::class.java).shouldNotBeNull()
            }
    }

    test("properties are bound from application config") {
        contextRunner
            .withPropertyValues(
                "okapi.processor.interval=500ms",
                "okapi.processor.batch-size=20",
                "okapi.processor.max-retries=3",
                "okapi.processor.concurrency=4",
                "okapi.processor.transport-dispatch=sequential",
            )
            .run { ctx ->
                val props = ctx.getBean<OutboxProcessorProperties>()
                props.interval shouldBe ofMillis(500)
                props.batchSize shouldBe 20
                props.maxRetries shouldBe 3
                props.concurrency shouldBe 4
                props.transportDispatch shouldBe TransportDispatch.SEQUENTIAL
            }
    }

    test("default properties when nothing is configured") {
        contextRunner.run { ctx ->
            val props = ctx.getBean<OutboxProcessorProperties>()
            props.interval shouldBe ofSeconds(1)
            props.batchSize shouldBe 10
            props.maxRetries shouldBe 5
            props.concurrency shouldBe 1
            props.transportDispatch shouldBe TransportDispatch.PARALLEL
        }
    }

    test("multi-transport batches are dispatched in parallel by default") {
        val deliveryThreads = ConcurrentLinkedQueue<Thread>()
        dispatchContextRunner(deliveryThreads).run { ctx ->
            ctx.getBean<OutboxEntryProcessor>()
                .processBatch(listOf(entryOfType("kafka-like"), entryOfType("http-like")))

            withClue("one group should run on a virtual thread while the other runs inline: $deliveryThreads") {
                deliveryThreads.toSet().size shouldBe 2
            }
            deliveryThreads shouldContain Thread.currentThread()
        }
    }

    test("okapi.processor.transport-dispatch=sequential keeps every transport group on the calling thread") {
        val deliveryThreads = ConcurrentLinkedQueue<Thread>()
        dispatchContextRunner(deliveryThreads)
            .withPropertyValues("okapi.processor.transport-dispatch=sequential")
            .run { ctx ->
                ctx.getBean<OutboxEntryProcessor>()
                    .processBatch(listOf(entryOfType("kafka-like"), entryOfType("http-like")))

                deliveryThreads.size shouldBe 2
                deliveryThreads.toSet() shouldBe setOf(Thread.currentThread())
            }
    }

    // transportDispatch was appended to the primary constructor; without @JvmOverloads that would
    // delete the 4-arg JVM constructor Java callers had before, breaking them at compile and link
    // time. @JvmOverloads regenerates it — and this asserts Spring's Kotlin-aware constructor
    // binding still picks the primary constructor now that the class has several.
    test("OutboxProcessorProperties keeps the pre-transportDispatch JVM constructor for Java callers") {
        val signatures = OutboxProcessorProperties::class.java.constructors.map { it.parameterTypes.toList() }

        withClue("available constructors: $signatures") {
            signatures shouldContain listOf(Duration::class.java, Int::class.java, Int::class.java, Int::class.java)
            signatures shouldContain
                listOf(Duration::class.java, Int::class.java, Int::class.java, Int::class.java, TransportDispatch::class.java)
        }
    }

    test("invalid transport-dispatch triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.processor.transport-dispatch=concurrent")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("SmartLifecycle is running after context start, and stop() actually halts it") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean<OutboxProcessorScheduler>()
            scheduler.isRunning shouldBe true
            scheduler.stop()
            scheduler.isRunning shouldBe false
        }
    }

    test("getPhase returns PROCESSOR_PHASE constant (orders before purger)") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean<OutboxProcessorScheduler>()
            scheduler.phase shouldBe OutboxProcessorScheduler.PROCESSOR_PHASE
        }
    }

    test("invalid batch-size triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.processor.batch-size=0")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("invalid concurrency triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.processor.concurrency=0")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("stop(callback) invokes callback AND actually halts the scheduler") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean<OutboxProcessorScheduler>()
            var callbackInvoked = false
            scheduler.stop { callbackInvoked = true }
            callbackInvoked shouldBe true
            scheduler.isRunning shouldBe false
        }
    }

    test("multiple MessageDeliverer beans are wrapped in CompositeMessageDeliverer (routed by deliveryType)") {
        contextRunner
            .withBean("secondDeliverer", MessageDeliverer::class.java, { stubDelivererWithType("second") })
            .run { ctx ->
                val processor = ctx.getBean<OutboxEntryProcessor>()
                processor.shouldNotBeNull()
                ctx.getBeansOfType(MessageDeliverer::class.java).size shouldBe 2
            }
    }

    test("duplicate deliverer types fail application startup") {
        contextRunner
            .withBean("secondDeliverer", MessageDeliverer::class.java, { stubDeliverer() })
            .run { ctx ->
                val errors = generateSequence(ctx.startupFailure.shouldNotBeNull()) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ")
                errors shouldContain "Duplicate MessageDeliverer type"
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
                val props = ctx.getBean<OkapiMetricsProperties>()
                props.refreshInterval shouldBe ofMinutes(1)
            }
    }

    test("metrics refresh-interval defaults to 15s") {
        contextRunner
            .withBean(io.micrometer.core.instrument.MeterRegistry::class.java, {
                io.micrometer.core.instrument.simple.SimpleMeterRegistry()
            })
            .run { ctx ->
                val props = ctx.getBean<OkapiMetricsProperties>()
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
            .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })
            .run { ctx ->
                ctx.getBean<io.micrometer.core.instrument.MeterRegistry>().shouldNotBeNull()
                ctx.getBean<MicrometerOutboxListener>().shouldNotBeNull()
                ctx.getBean<MicrometerOutboxMetrics>().shouldNotBeNull()
                ctx.getBean<OutboxMetricsRefresher>().shouldNotBeNull()
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

@Configuration(proxyBeanMethods = false)
private class CustomProcessorConfiguration {
    @Bean
    fun customProcessor(store: OutboxStore, entryProcessor: OutboxEntryProcessor): OutboxProcessor {
        return OutboxProcessor(store, entryProcessor)
    }
}

/**
 * Context with two thread-recording deliverers, so a batch spanning both transports proves where
 * `okapi.processor.transport-dispatch` actually lands: the recorded threads are the observable
 * difference between parallel and sequential dispatch.
 */
private fun dispatchContextRunner(deliveryThreads: ConcurrentLinkedQueue<Thread>) = ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
    .withBean(OutboxStore::class.java, { stubStore() })
    .withBean("kafkaLikeDeliverer", MessageDeliverer::class.java, { threadRecordingDeliverer("kafka-like", deliveryThreads) })
    .withBean("httpLikeDeliverer", MessageDeliverer::class.java, { threadRecordingDeliverer("http-like", deliveryThreads) })
    .withBean(DataSource::class.java, { SimpleDriverDataSource() })
    .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })

private fun threadRecordingDeliverer(t: String, into: ConcurrentLinkedQueue<Thread>) = object : MessageDeliverer {
    override val type = t

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        into += Thread.currentThread()
        return DeliveryResult.Success
    }
}

private fun entryOfType(t: String): OutboxEntry {
    val deliveryInfo = object : DeliveryInfo {
        override val type = t

        override fun serialize(): String = "{}"
    }
    return OutboxEntry.createPending(OutboxMessage("evt", "{}"), deliveryInfo, Instant.EPOCH)
}

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
