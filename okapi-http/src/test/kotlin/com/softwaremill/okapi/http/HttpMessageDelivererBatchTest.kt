package com.softwaremill.okapi.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.softwaremill.okapi.core.DeliveryOutcome
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

private fun entry(suffix: String, path: String = "/test", metadataOverride: String? = null): OutboxEntry {
    val info = httpDeliveryInfo {
        serviceName = "svc"
        endpointPath = path
    }
    val base = OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
    return if (metadataOverride != null) base.copy(deliveryMetadata = metadataOverride) else base
}

class HttpMessageDelivererBatchTest : FunSpec({
    val wiremock = WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort())
    val deliverer by lazy {
        HttpMessageDeliverer(ServiceUrlResolver { "http://localhost:${wiremock.port()}" })
    }

    beforeSpec { wiremock.start() }
    afterSpec { wiremock.stop() }
    beforeEach { wiremock.resetAll() }

    test("deliverBatch on empty input returns empty list and fires no requests") {
        deliverer.deliverBatch(emptyList()) shouldBe emptyList()
        wiremock.allServeEvents.size shouldBe 0
    }

    test("deliverBatch with all-success preserves input order") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 3
        results.map { it.entry } shouldBe entries
        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }
    }

    test("deliverBatch with mixed status codes classifies each entry independently, in order") {
        wiremock.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)))
        wiremock.stubFor(post(urlEqualTo("/retriable")).willReturn(aResponse().withStatus(500)))
        wiremock.stubFor(post(urlEqualTo("/permanent")).willReturn(aResponse().withStatus(400)))
        val entries = listOf(
            entry("a", path = "/ok"),
            entry("b", path = "/retriable"),
            entry("c", path = "/permanent"),
        )

        val results = deliverer.deliverBatch(entries)

        results.map { it.entry } shouldBe entries
        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
        results[2].result.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    test("deliverBatch with all-fail batch classifies every entry as RetriableFailure") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(503)))
        val entries = listOf(entry("a"), entry("b"), entry("c"))

        val results = deliverer.deliverBatch(entries)

        results.size shouldBe 3
        results.forEach { (_, r) -> r.shouldBeInstanceOf<DeliveryResult.RetriableFailure>() }
    }

    test("deliverBatch connection reset -> RetriableFailure for the affected entry") {
        wiremock.stubFor(
            post(urlEqualTo("/test")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val results = deliverer.deliverBatch(listOf(entry("a")))

        results[0].result.shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("deliverBatch poison-pill metadata yields PermanentFailure for bad entry, others unaffected") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val good1 = entry("good1")
        val poisoned = entry("bad", metadataOverride = "{not valid http info json}")
        val good2 = entry("good2")

        val results = deliverer.deliverBatch(listOf(good1, poisoned, good2))

        results.size shouldBe 3
        results.map { it.entry } shouldBe listOf(good1, poisoned, good2)
        results[0].result shouldBe DeliveryResult.Success
        results[1].result.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
        results[2].result shouldBe DeliveryResult.Success
        // Only the two well-formed entries actually reached the server.
        wiremock.allServeEvents.size shouldBe 2
    }

    test("deliverBatch with a custom HttpClient uses it for every entry in the batch") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val customClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val custom = HttpMessageDeliverer(ServiceUrlResolver { "http://localhost:${wiremock.port()}" }, customClient)
        val entries = listOf(entry("a"), entry("b"))

        val results = custom.deliverBatch(entries)

        results.forEach { (_, r) -> r shouldBe DeliveryResult.Success }
        wiremock.allServeEvents.size shouldBe 2
    }

    test("deliverBatch TLS handshake failure -> PermanentFailure (does not throw)") {
        // WireMock's HTTPS port serves its own self-signed cert, which the JDK's default trust
        // store rejects -- a deterministic SSLHandshakeException (cert/config problem, won't fix
        // itself on retry). Deliberately not testing this via https:// against the *plaintext*
        // port: that failure mode depends on exactly how/when the peer aborts the connection, and
        // the exception type it produces is a genuine JDK-level race -- usually SSLException, but
        // occasionally java.net.http.HttpConnectTimeoutException (an IOException, misclassified
        // as RetriableFailure), causing sporadic CI failures.
        val tlsDeliverer = HttpMessageDeliverer(ServiceUrlResolver { "https://localhost:${wiremock.httpsPort()}" })

        val outcome: DeliveryOutcome = tlsDeliverer.deliverBatch(listOf(entry("a"))).single()

        outcome.result.shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }
})
