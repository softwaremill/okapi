package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
private val retryPolicy = RetryPolicy(maxRetries = 3)
private val stubDeliveryInfo =
    object : DeliveryInfo {
        override val type = "stub"

        override fun serialize(): String = """{"type":"stub"}"""
    }

private fun stubDeliverer(result: DeliveryResult) = object : MessageDeliverer {
    override val type = "stub"

    override fun deliver(entry: OutboxEntry) = result
}

private fun pendingEntry(retries: Int = 0): OutboxEntry =
    OutboxEntry.createPending(OutboxMessage("test.event", """{"k":"v"}"""), stubDeliveryInfo, Instant.EPOCH)
        .copy(retries = retries)

class OutboxEntryProcessorTest :
    BehaviorSpec({
        given("process() — DeliveryResult.Success") {
            `when`("called") {
                val processor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.Success),
                        retryPolicy = retryPolicy,
                        clock = fixedClock,
                    )
                val result = processor.process(pendingEntry())

                then("status is DELIVERED") {
                    result.status shouldBe OutboxStatus.DELIVERED
                }
                then("lastAttempt is set to now") {
                    result.lastAttempt shouldBe fixedClock.instant()
                }
                then("lastError is null") {
                    result.lastError shouldBe null
                }
            }
        }

        given("process() — RetriableFailure with retries remaining") {
            `when`("retries=1, maxRetries=3") {
                val processor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.RetriableFailure("timeout")),
                        retryPolicy = retryPolicy,
                        clock = fixedClock,
                    )
                val result = processor.process(pendingEntry(retries = 1))

                then("status stays PENDING") {
                    result.status shouldBe OutboxStatus.PENDING
                }
                then("retries is incremented") {
                    result.retries shouldBe 2
                }
                then("lastError is set") {
                    result.lastError shouldBe "timeout"
                }
            }
        }

        given("process() — RetriableFailure with no retries remaining") {
            `when`("retries=3, maxRetries=3") {
                val processor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.RetriableFailure("timeout")),
                        retryPolicy = retryPolicy,
                        clock = fixedClock,
                    )
                val result = processor.process(pendingEntry(retries = 3))

                then("status is FAILED") {
                    result.status shouldBe OutboxStatus.FAILED
                }
                then("lastError is set") {
                    result.lastError shouldBe "timeout"
                }
            }
        }

        given("processBatch() — mixed results") {
            `when`("called with three entries returning Success, RetriableFailure, PermanentFailure") {
                val deliverer = object : MessageDeliverer {
                    override val type = "stub"
                    private val results = listOf(
                        DeliveryResult.Success,
                        DeliveryResult.RetriableFailure("retry me"),
                        DeliveryResult.PermanentFailure("never"),
                    )
                    private var idx = 0
                    override fun deliver(entry: OutboxEntry): DeliveryResult = results[idx++]
                }
                val processor = OutboxEntryProcessor(deliverer, retryPolicy, fixedClock)
                val entries = listOf(pendingEntry(), pendingEntry(), pendingEntry())
                val results = processor.processBatch(entries)

                then("preserves input order") {
                    results.size shouldBe 3
                }
                then("first entry is DELIVERED") {
                    results[0].status shouldBe OutboxStatus.DELIVERED
                }
                then("second entry is PENDING (retriable, retries remaining)") {
                    results[1].status shouldBe OutboxStatus.PENDING
                    results[1].lastError shouldBe "retry me"
                }
                then("third entry is FAILED (permanent)") {
                    results[2].status shouldBe OutboxStatus.FAILED
                    results[2].lastError shouldBe "never"
                }
            }
        }

        given("processBatch() — empty input") {
            `when`("called with empty list") {
                val processor = OutboxEntryProcessor(stubDeliverer(DeliveryResult.Success), retryPolicy, fixedClock)
                val results = processor.processBatch(emptyList())

                then("returns empty list without invoking deliverer") {
                    results shouldBe emptyList()
                }
            }
        }

        given("process() — PermanentFailure") {
            `when`("called with retries=0") {
                val processor =
                    OutboxEntryProcessor(
                        deliverer = stubDeliverer(DeliveryResult.PermanentFailure("400 Bad Request")),
                        retryPolicy = retryPolicy,
                        clock = fixedClock,
                    )
                val result = processor.process(pendingEntry(retries = 0))

                then("status is FAILED immediately") {
                    result.status shouldBe OutboxStatus.FAILED
                }
                then("retries not incremented") {
                    result.retries shouldBe 0
                }
                then("lastError is set") {
                    result.lastError shouldBe "400 Bad Request"
                }
            }
        }
    })
