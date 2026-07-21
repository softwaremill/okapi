package com.softwaremill.okapi.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.DeliveryOutcome
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private fun entry(suffix: String): OutboxEntry {
    val info = httpDeliveryInfo {
        serviceName = "svc"
        endpointPath = "/test"
    }
    return OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
}

/**
 * Polls (rather than sleeping a fixed duration) until [thread] is parked waiting on the future/
 * response it's blocked on, so the test isn't sensitive to CI scheduling jitter — interrupting
 * before the thread actually blocks would make it miss the wait entirely.
 */
private fun awaitBlocked(thread: Thread, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (thread.state != Thread.State.WAITING && thread.state != Thread.State.TIMED_WAITING) {
        check(System.currentTimeMillis() < deadline) { "Thread never blocked (state=${thread.state})" }
        Thread.sleep(5)
    }
}

/**
 * A timed [Thread.join] only establishes a happens-before edge for the joined thread's writes if
 * it returns because the thread actually terminated, not because the timeout elapsed — if it
 * times out and the thread happens to die a moment later, `isAlive` can read `false` with no
 * visibility guarantee for what the thread wrote. Confirming termination and then joining again
 * unconditionally (returns immediately on an already-dead thread) closes that gap.
 */
private fun awaitTermination(thread: Thread, timeoutMs: Long = 5_000) {
    thread.join(timeoutMs)
    check(!thread.isAlive) { "Thread did not terminate within ${timeoutMs}ms" }
    thread.join()
}

/**
 * Proves that interrupting the calling thread while it is blocked inside [HttpMessageDeliverer]
 * is observed promptly and the interrupt flag ends up restored on that same thread — not on some
 * unrelated `HttpClient` completion thread — for both the synchronous and batched delivery paths.
 */
class HttpMessageDelivererInterruptionTest : FunSpec({
    val wiremock = WireMockServer(wireMockConfig().dynamicPort())
    val deliverer by lazy {
        HttpMessageDeliverer(ServiceUrlResolver { "http://localhost:${wiremock.port()}" })
    }

    beforeSpec { wiremock.start() }
    afterSpec { wiremock.stop() }
    beforeEach { wiremock.resetAll() }

    test("interrupting the caller during deliver() yields RetriableFailure and restores the flag on that thread") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)))

        var result: DeliveryResult? = null
        var interruptedAfterReturn = false
        val thread = Thread {
            result = deliverer.deliver(entry("a"))
            interruptedAfterReturn = Thread.currentThread().isInterrupted
        }
        thread.start()
        awaitBlocked(thread)
        thread.interrupt()
        awaitTermination(thread)

        result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
        interruptedAfterReturn shouldBe true
    }

    test(
        "interrupting the caller while awaiting deliverBatch yields RetriableFailure for every entry " +
            "and restores the flag on that thread",
    ) {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)))
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        var results: List<DeliveryOutcome>? = null
        var interruptedAfterReturn = false
        val thread = Thread {
            results = deliverer.deliverBatch(entries)
            interruptedAfterReturn = Thread.currentThread().isInterrupted
        }
        thread.start()
        awaitBlocked(thread)
        thread.interrupt()
        awaitTermination(thread)

        results?.size shouldBe 3
        results?.forEach { it.result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>() }
        interruptedAfterReturn shouldBe true
    }
})
