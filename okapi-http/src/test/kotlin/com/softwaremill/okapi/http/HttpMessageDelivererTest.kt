package com.softwaremill.okapi.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxHeaders
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.WithDataTestName
import io.kotest.datatest.withTests
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

private data class StatusCodeCase(
    val statusCode: Int,
    val expectedType: KClass<out DeliveryResult>,
) : WithDataTestName {
    override fun dataTestName() = "$statusCode -> ${expectedType.simpleName}"
}

class HttpMessageDelivererTest : FunSpec({
    val wiremock = WireMockServer(wireMockConfig().dynamicPort())
    val deliverer by lazy {
        HttpMessageDeliverer(ServiceUrlResolver { "http://localhost:${wiremock.port()}" })
    }

    beforeSpec { wiremock.start() }
    afterSpec { wiremock.stop() }
    beforeEach { wiremock.resetAll() }

    fun entry(): OutboxEntry {
        val info = httpDeliveryInfo {
            serviceName = "svc"
            endpointPath = "/test"
        }
        return OutboxEntry.createPending(OutboxMessage("test", """{"k":"v"}"""), info, Instant.now())
    }

    context("status code mapping") {
        withTests(
            StatusCodeCase(200, DeliveryResult.Success::class),
            StatusCodeCase(500, DeliveryResult.RetriableFailure::class),
            StatusCodeCase(429, DeliveryResult.RetriableFailure::class),
            StatusCodeCase(408, DeliveryResult.RetriableFailure::class),
            StatusCodeCase(400, DeliveryResult.PermanentFailure::class),
        ) { (statusCode, expectedType) ->
            wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(statusCode)))
            deliverer.deliver(entry())::class shouldBe expectedType
        }
    }

    test("custom retriable codes") {
        val custom = HttpMessageDeliverer(
            ServiceUrlResolver { "http://localhost:${wiremock.port()}" },
            retriableStatusCodes = HttpMessageDeliverer.DEFAULT_RETRIABLE_STATUS_CODES + 401,
        )
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(401)))
        custom.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("sends Content-Type application/json by default") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        deliverer.deliver(entry())
        wiremock.verify(postRequestedFor(urlEqualTo("/test")).withHeader("Content-Type", equalTo("application/json")))
    }

    test("user header overrides Content-Type") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val info = httpDeliveryInfo {
            serviceName = "svc"
            endpointPath = "/test"
            header("Content-Type", "text/plain")
        }
        val e = OutboxEntry.createPending(OutboxMessage("test", "plain text"), info, Instant.now())
        deliverer.deliver(e)
        wiremock.verify(postRequestedFor(urlEqualTo("/test")).withHeader("Content-Type", equalTo("text/plain")))
    }

    test("sends x-outbox-id carrying the entry's UUID") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val e = entry()

        deliverer.deliver(e)

        wiremock.verify(
            postRequestedFor(urlEqualTo("/test"))
                .withHeader(OutboxHeaders.OUTBOX_ID, equalTo(e.outboxId.raw.toString())),
        )
    }

    test("okapi's x-outbox-id replaces a caller-supplied header of the same name") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        val info = httpDeliveryInfo {
            serviceName = "svc"
            endpointPath = "/test"
            header(OutboxHeaders.OUTBOX_ID, "caller-supplied")
        }
        val e = OutboxEntry.createPending(OutboxMessage("test", """{"k":"v"}"""), info, Instant.now())

        deliverer.deliver(e)

        // setHeader replaces, so unlike Kafka the caller's value is gone, not merely shadowed.
        wiremock.verify(
            postRequestedFor(urlEqualTo("/test"))
                .withHeader(OutboxHeaders.OUTBOX_ID, equalTo(e.outboxId.raw.toString())),
        )
    }

    test("connection error -> RetriableFailure") {
        wiremock.stubFor(
            post(urlEqualTo("/test"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)),
        )
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("corrupt delivery metadata -> PermanentFailure (does not throw)") {
        val poisoned = entry().copy(deliveryMetadata = "{not valid json")
        deliverer.deliver(poisoned).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    test("urlResolver throwing -> PermanentFailure (does not throw)") {
        val throwingResolver = ServiceUrlResolver { name -> error("Unknown service: $name") }
        val d = HttpMessageDeliverer(throwingResolver)
        d.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
    }

    context("response bodies are bounded (#130)") {
        val hugeErrorPage = "<html>" + "x".repeat(1_000_000) + "</html>"
        val maxErrorLength = "HTTP 503: ".length + HttpMessageDeliverer.MAX_ERROR_BODY_BYTES + OutboxEntry.TRUNCATION_MARKER.length

        test("deliver() keeps only MAX_ERROR_BODY_BYTES of an error body") {
            wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(503).withBody(hugeErrorPage)))
            val error = deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>().error
            error shouldStartWith "HTTP 503: <html>xxx"
            error shouldEndWith OutboxEntry.TRUNCATION_MARKER
            error.length shouldBeLessThanOrEqual maxErrorLength
        }

        test("deliverBatch() keeps only MAX_ERROR_BODY_BYTES of an error body") {
            wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(400).withBody(hugeErrorPage)))
            val outcomes = deliverer.deliverBatch(listOf(entry(), entry()))
            outcomes.forEach { outcome ->
                val error = outcome.result.shouldBeInstanceOf<DeliveryResult.PermanentFailure>().error
                error shouldEndWith OutboxEntry.TRUNCATION_MARKER
                error.length shouldBeLessThanOrEqual maxErrorLength
            }
        }

        test("a short error body is kept whole") {
            wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(500).withBody("Internal Server Error")))
            deliverer.deliver(entry()) shouldBe DeliveryResult.RetriableFailure("HTTP 500: Internal Server Error")
        }

        test("a large success body is discarded and still counts as Success") {
            wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200).withBody(hugeErrorPage)))
            deliverer.deliver(entry()) shouldBe DeliveryResult.Success
            deliverer.deliverBatch(listOf(entry())).single().result shouldBe DeliveryResult.Success
        }

        fun elapsedMillis(block: () -> Unit): Long {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1_000_000
        }

        test("a slow body is not read past MAX_DRAINED_BODY_BYTES") {
            // 1 MB in 100 chunks over 20 s: reading it all would take 20 s, the read cap is hit in ~1.5 s.
            wiremock.stubFor(
                post(urlEqualTo("/test")).willReturn(
                    aResponse().withStatus(200).withBody(hugeErrorPage).withChunkedDribbleDelay(100, 20_000),
                ),
            )
            elapsedMillis { deliverer.deliver(entry()) shouldBe DeliveryResult.Success } shouldBeLessThan 10_000
            elapsedMillis {
                deliverer.deliverBatch(listOf(entry())).single().result shouldBe DeliveryResult.Success
            } shouldBeLessThan 10_000
        }

        test("an exchange whose body outlasts the exchange timeout is cancelled as a RetriableFailure") {
            // A small error body dripped over 20 s: under the read cap, so only the deadline stops it.
            wiremock.stubFor(
                post(urlEqualTo("/test")).willReturn(
                    aResponse().withStatus(503).withBody("x".repeat(1_000)).withChunkedDribbleDelay(20, 20_000),
                ),
            )
            val impatient = HttpMessageDeliverer(
                ServiceUrlResolver { "http://localhost:${wiremock.port()}" },
                HttpClient.newHttpClient(),
                HttpMessageDeliverer.DEFAULT_RETRIABLE_STATUS_CODES,
                Duration.ofSeconds(1),
            )
            val expected = DeliveryResult.RetriableFailure("HTTP exchange did not complete within PT1S")
            elapsedMillis { impatient.deliver(entry()) shouldBe expected } shouldBeLessThan 10_000
            elapsedMillis {
                impatient.deliverBatch(listOf(entry(), entry())).map { it.result } shouldBe listOf(expected, expected)
            } shouldBeLessThan 10_000
        }
    }
})
