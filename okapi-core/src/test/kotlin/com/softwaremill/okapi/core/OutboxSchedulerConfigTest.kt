package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Duration.ofMillis
import java.time.Duration.ofNanos
import java.time.Duration.ofSeconds
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class OutboxSchedulerConfigTest : FunSpec({

    test("default config has valid values") {
        val config = OutboxSchedulerConfig()
        config.interval shouldBe ofSeconds(1)
        config.batchSize shouldBe 10
        config.concurrency shouldBe 1
    }

    test("accepts custom valid values") {
        val config = OutboxSchedulerConfig(interval = ofMillis(500), batchSize = 50, concurrency = 8)
        config.interval shouldBe ofMillis(500)
        config.batchSize shouldBe 50
        config.concurrency shouldBe 8
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

    test("accepts concurrency boundaries 1 and 256") {
        OutboxSchedulerConfig(concurrency = 1).concurrency shouldBe 1
        OutboxSchedulerConfig(concurrency = 256).concurrency shouldBe 256
    }

    test("rejects zero concurrency") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(concurrency = 0)
        }
    }

    test("rejects negative concurrency") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(concurrency = -1)
        }
    }

    test("rejects concurrency above 256") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig(concurrency = 257)
        }
    }

    test("defaultPlatformPool runs submitted tasks and names its threads outbox-worker-N") {
        val pool = OutboxSchedulerConfig.defaultPlatformPool(2)
        try {
            val threadNames = (1..2).map { pool.submit(Callable { Thread.currentThread().name }) }
                .map { it.get(2, TimeUnit.SECONDS) }
            threadNames.toSet() shouldBe setOf("outbox-worker-1", "outbox-worker-2")
        } finally {
            pool.shutdown()
        }
    }

    test("defaultPlatformPool rejects zero or negative n") {
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig.defaultPlatformPool(0)
        }
        shouldThrow<IllegalArgumentException> {
            OutboxSchedulerConfig.defaultPlatformPool(-1)
        }
    }

    test("virtualThreadPool runs submitted tasks on virtual threads") {
        val pool = OutboxSchedulerConfig.virtualThreadPool(4)
        try {
            val isVirtual = pool.submit(Callable { Thread.currentThread().isVirtual }).get(2, TimeUnit.SECONDS)
            isVirtual shouldBe true
        } finally {
            pool.shutdown()
        }
    }
})
