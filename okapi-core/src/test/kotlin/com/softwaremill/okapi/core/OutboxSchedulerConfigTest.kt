package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OutboxSchedulerConfigTest : FunSpec({

    test("default config has valid values") {
        val config = OutboxSchedulerConfig()
        config.intervalMs shouldBe 1_000L
        config.batchSize shouldBe 10
    }

    test("accepts custom valid values") {
        val config = OutboxSchedulerConfig(intervalMs = 500, batchSize = 50)
        config.intervalMs shouldBe 500L
        config.batchSize shouldBe 50
    }

    test("rejects zero intervalMs") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(intervalMs = 0)
        }
    }

    test("rejects negative intervalMs") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(intervalMs = -1)
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
