package com.softwaremill.okapi.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.random.RandomGenerator

/** A [RandomGenerator] whose `nextDouble()` always returns [value]. */
private fun fixedRandom(value: Double) = object : RandomGenerator {
    override fun nextLong(): Long = 0
    override fun nextDouble(): Double = value
}

class RetryPolicyTest : FunSpec({
    val noJitter = RetryPolicy(
        maxRetries = 10,
        initialBackoff = Duration.ofSeconds(10),
        backoffMultiplier = 2.0,
        maxBackoff = Duration.ofMinutes(1),
        jitter = 0.0,
    )

    test("backoff grows exponentially from initialBackoff and is capped at maxBackoff") {
        (1..5).map { noJitter.backoff(it) } shouldBe listOf(
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(40),
            Duration.ofMinutes(1),
            Duration.ofMinutes(1),
        )
    }

    test("a large retry number does not overflow") {
        noJitter.backoff(10_000) shouldBe Duration.ofMinutes(1)
    }

    test("jitter shortens the delay by at most the jitter fraction") {
        val policy = noJitter.copy(jitter = 0.2)
        policy.backoff(1, fixedRandom(0.0)) shouldBe Duration.ofSeconds(10)
        policy.backoff(1, fixedRandom(0.5)) shouldBe Duration.ofSeconds(9)
        repeat(1_000) {
            val delay = policy.backoff(2)
            delay shouldBeGreaterThanOrEqualTo Duration.ofSeconds(16)
            delay shouldBeLessThanOrEqualTo Duration.ofSeconds(20)
        }
    }

    test("defaults give a retry window of minutes, not seconds") {
        val policy = RetryPolicy(maxRetries = 5)
        val total = (1..5).map { policy.backoff(it, fixedRandom(0.0)) }.reduce(Duration::plus)
        total shouldBe Duration.ofSeconds(10 + 20 + 40 + 80 + 160)
    }

    test("invalid settings are rejected") {
        shouldThrow<IllegalArgumentException> { RetryPolicy(1, initialBackoff = Duration.ofSeconds(-1)) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(1, backoffMultiplier = 0.5) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(1, initialBackoff = Duration.ofMinutes(2), maxBackoff = Duration.ofMinutes(1)) }
        shouldThrow<IllegalArgumentException> { RetryPolicy(1, jitter = 1.5) }
        shouldThrow<IllegalArgumentException> { noJitter.backoff(0) }
    }

    test("a retriable failure schedules the next attempt after the backoff") {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val deliverer = object : MessageDeliverer {
            override val type = "stub"
            override fun deliver(entry: OutboxEntry) = DeliveryResult.RetriableFailure("503")
        }
        val info = object : DeliveryInfo {
            override val type = "stub"
            override fun serialize(): String = """{"type":"stub"}"""
        }
        val processor = OutboxEntryProcessor(deliverer, noJitter, Clock.fixed(now, ZoneOffset.UTC))
        val entry = OutboxEntry.createPending(OutboxMessage("e", "{}"), info, Instant.EPOCH)

        val first = processor.process(entry)
        first.nextAttemptAt shouldBe now.plusSeconds(10)
        processor.process(first).nextAttemptAt shouldBe now.plusSeconds(20)
    }

    test("a new entry is due when it is created") {
        val info = object : DeliveryInfo {
            override val type = "stub"
            override fun serialize(): String = "{}"
        }
        val createdAt = Instant.parse("2024-05-01T00:00:00Z")
        OutboxEntry.createPending(OutboxMessage("e", "{}"), info, createdAt).nextAttemptAt shouldBe createdAt
    }
})
