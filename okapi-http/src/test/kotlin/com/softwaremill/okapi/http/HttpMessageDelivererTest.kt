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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

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

    test("200 → Success") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(200)))
        deliverer.deliver(entry()) shouldBe DeliveryResult.Success
    }

    test("500 → RetriableFailure") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(500)))
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("429 → RetriableFailure") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(429)))
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("408 → RetriableFailure") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(408)))
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }

    test("400 → PermanentFailure") {
        wiremock.stubFor(post(urlEqualTo("/test")).willReturn(aResponse().withStatus(400)))
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.PermanentFailure>()
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

    test("connection error → RetriableFailure") {
        wiremock.stubFor(
            post(urlEqualTo("/test"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)),
        )
        deliverer.deliver(entry()).shouldBeInstanceOf<DeliveryResult.RetriableFailure>()
    }
})
