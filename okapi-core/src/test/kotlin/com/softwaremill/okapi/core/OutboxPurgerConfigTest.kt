package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class OutboxPurgerConfigTest : FunSpec({

    test("default config has valid values") {
        val config = OutboxPurgerConfig()
        config.retention shouldBe Duration.ofDays(7)
        config.interval shouldBe Duration.ofHours(1)
        config.batchSize shouldBe 100
    }

    test("accepts custom valid values") {
        val config = OutboxPurgerConfig(
            retention = Duration.ofHours(12),
            interval = Duration.ofSeconds(30),
            batchSize = 50,
        )
        config.retention shouldBe Duration.ofHours(12)
        config.interval shouldBe Duration.ofSeconds(30)
        config.batchSize shouldBe 50
    }

    test("rejects zero retention") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(retention = Duration.ZERO)
        }
    }

    test("rejects negative retention") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(retention = Duration.ofDays(-1))
        }
    }

    test("rejects zero interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(interval = Duration.ZERO)
        }
    }

    test("rejects negative interval") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(interval = Duration.ofMinutes(-5))
        }
    }

    test("rejects zero batchSize") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(batchSize = 0)
        }
    }

    test("rejects negative batchSize") {
        shouldThrow<IllegalArgumentException> {
            OutboxPurgerConfig(batchSize = -10)
        }
    }
})
