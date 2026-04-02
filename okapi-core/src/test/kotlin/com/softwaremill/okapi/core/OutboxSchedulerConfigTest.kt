package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Duration.ofMillis
import java.time.Duration.ofNanos
import java.time.Duration.ofSeconds

class OutboxSchedulerConfigTest : FunSpec({

    test("default config has valid values") {
        val config = OutboxSchedulerConfig()
        config.interval shouldBe ofSeconds(1)
        config.batchSize shouldBe 10
    }

    test("accepts custom valid values") {
        val config = OutboxSchedulerConfig(interval = ofMillis(500), batchSize = 50)
        config.interval shouldBe ofMillis(500)
        config.batchSize shouldBe 50
    }

    test("rejects zero interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(interval = Duration.ZERO)
        }
    }

    test("rejects negative interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(interval = ofMillis(-1))
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

    test("rejects sub-millisecond interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(interval = ofNanos(1))
        }
    }
})
