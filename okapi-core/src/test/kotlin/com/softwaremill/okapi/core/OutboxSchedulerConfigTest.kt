package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class OutboxSchedulerConfigTest : FunSpec({

    test("default config has valid values") {
        val config = OutboxSchedulerConfig()
        config.interval shouldBe Duration.ofSeconds(1)
        config.batchSize shouldBe 10
    }

    test("accepts custom valid values") {
        val config = OutboxSchedulerConfig(interval = Duration.ofMillis(500), batchSize = 50)
        config.interval shouldBe Duration.ofMillis(500)
        config.batchSize shouldBe 50
    }

    test("rejects zero interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(interval = Duration.ZERO)
        }
    }

    test("rejects negative interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(interval = Duration.ofMillis(-1))
        }
    }

    test("rejects zero batchSize") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(batchSize = 0)
        }
    }

    test("rejects negative batchSize") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(batchSize = -5)
        }
    }
})
