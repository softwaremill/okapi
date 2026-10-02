package com.softwaremill.okapi.http

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val RENDEZVOUS_TIMEOUT_SECONDS = 10L

private fun entry(suffix: String): OutboxEntry {
    val info = httpDeliveryInfo {
        serviceName = "svc"
        endpointPath = "/test"
    }
    return OutboxEntry.createPending(OutboxMessage("evt-$suffix", """{"k":"v-$suffix"}"""), info, Instant.now())
}

class HttpMessageDelivererBatchConcurrencyTest : FunSpec({
    test("deliverBatch keeps every request in flight before any response completes") {
        val batchSize = 10
        val barrier = CyclicBarrier(batchSize)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/test") { exchange ->
                exchange.use {
                    it.requestBody.use { body -> body.readAllBytes() }
                    // Every handler waits for the whole batch before responding. Sequential
                    // delivery breaks the barrier and gets 503s; parallel delivery gets 200s.
                    val status = try {
                        barrier.await(RENDEZVOUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        200
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        503
                    } catch (_: BrokenBarrierException) {
                        503
                    } catch (_: TimeoutException) {
                        503
                    }
                    it.sendResponseHeaders(status, -1)
                }
            }

            try {
                server.start()
                val deliverer = HttpMessageDeliverer({ "http://127.0.0.1:${server.address.port}" })
                val entries = (1..batchSize).map { entry("e$it") }

                val results = deliverer.deliverBatch(entries)

                withClue("All requests must reach the server before it sends any successful response") {
                    results.map { it.result } shouldBe List(batchSize) { DeliveryResult.Success }
                }
            } finally {
                server.stop(0)
            }
        }
    }
})
