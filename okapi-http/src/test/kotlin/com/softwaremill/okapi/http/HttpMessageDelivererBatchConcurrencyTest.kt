package com.softwaremill.okapi.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.time.Instant

private fun entry(suffix: String): OutboxEntry {
    val info = httpDeliveryInfo {
        serviceName = "svc"
        endpointPath = "/test"
    }
    return OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
}

/**
 * Proves `deliverBatch` fires requests concurrently rather than one-at-a-time, using WireMock's
 * request journal (`allServeEvents`) rather than just overall wall-clock time — the timestamp
 * spread across requests is direct evidence they overlapped in flight.
 */
class HttpMessageDelivererBatchConcurrencyTest : FunSpec({
    val wiremock = WireMockServer(wireMockConfig().dynamicPort())
    val deliverer by lazy {
        HttpMessageDeliverer(ServiceUrlResolver { "http://localhost:${wiremock.port()}" })
    }

    beforeSpec { wiremock.start() }
    afterSpec { wiremock.stop() }
    beforeEach { wiremock.resetAll() }

    test("deliverBatch fires N requests concurrently: request-log timestamps overlap within one delay window") {
        val batchSize = 10
        val delayMs = 300L
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200).withFixedDelay(delayMs.toInt())))
        val entries = (1..batchSize).map { entry("e$it") }

        val start = System.currentTimeMillis()
        val results = deliverer.deliverBatch(entries)
        val elapsedMs = System.currentTimeMillis() - start

        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }

        // Sequential delivery would take ~batchSize * delayMs; parallel delivery should land near
        // one delay window plus scheduling overhead — well under half the sequential bound.
        elapsedMs.shouldBeLessThan(batchSize * delayMs / 2)

        // Direct evidence of overlap: every request's logged arrival time falls within a single
        // delay window of each other, i.e. WireMock received all of them before the first one
        // could possibly have completed and freed up a sequential caller to send the next.
        val loggedTimestamps = wiremock.allServeEvents.map { it.request.loggedDate.time }
        loggedTimestamps.size shouldBe batchSize
        val spreadMs = loggedTimestamps.max() - loggedTimestamps.min()
        spreadMs.shouldBeLessThan(delayMs)
    }
})
