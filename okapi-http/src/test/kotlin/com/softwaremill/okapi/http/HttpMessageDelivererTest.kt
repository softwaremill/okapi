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
import com.softwaremill.okapi.core.OutboxMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.WithDataTestName
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    test("connection error -> RetriableFailure") {
        wiremock.stubFor(
            post(urlEqualTo("/test"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)),
        )
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }
})
