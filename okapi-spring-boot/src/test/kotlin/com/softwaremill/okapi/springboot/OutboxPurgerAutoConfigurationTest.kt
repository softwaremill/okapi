package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxPurgerConfig
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.time.Duration.ofDays
import java.time.Duration.ofHours
import java.time.Duration.ofMinutes
import javax.sql.DataSource

class OutboxPurgerAutoConfigurationTest : FunSpec({

    val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withBean(OutboxStore::class.java, { stubStore() })
        .withBean(MessageDeliverer::class.java, { stubDeliverer() })
        .withBean(DataSource::class.java, { SimpleDriverDataSource() })
        .withBean(TransactionRunner::class.java, { noOpTransactionRunner() })

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

    test("SmartLifecycle is running after context start, and stop() actually halts it") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxPurgerScheduler::class.java)
            scheduler.isRunning shouldBe true
            scheduler.stop()
            scheduler.isRunning shouldBe false
        }
    }

    test("getPhase returns PURGER_PHASE constant (orders after processor)") {
        contextRunner.run { ctx ->
            val scheduler = ctx.getBean(OutboxPurgerScheduler::class.java)
            scheduler.phase shouldBe OutboxPurgerScheduler.PURGER_PHASE
        }
    }

    test("invalid retention triggers startup failure") {
        contextRunner
            .withPropertyValues("okapi.purger.retention=0s")
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
            }
    }

    test("stop(callback) invokes callback AND actually halts the purger") {
        val scheduler = OutboxPurgerScheduler(
            outboxStore = stubStore(),
            transactionRunner = noOpTransactionRunner(),
            config = OutboxPurgerConfig(interval = ofMinutes(60)),
        )
        scheduler.start()

        var callbackInvoked = false
        scheduler.stop { callbackInvoked = true }

        callbackInvoked shouldBe true
        scheduler.isRunning shouldBe false
    }
})
