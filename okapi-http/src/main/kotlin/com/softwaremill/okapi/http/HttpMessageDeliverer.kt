package com.softwaremill.okapi.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.softwaremill.okapi.core.DeliveryOutcome
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLException

/**
 * [MessageDeliverer] that sends outbox entries as HTTP requests via JDK [HttpClient].
 *
 * Status code classification:
 * - 2xx → [DeliveryResult.Success]
 * - 5xx, 429, 408 → [DeliveryResult.RetriableFailure] (configurable via [retriableStatusCodes])
 * - other → [DeliveryResult.PermanentFailure]
 *
 * Exception classification:
 * - [SSLException] (bad cert, protocol mismatch — won't fix itself) → [DeliveryResult.PermanentFailure]
 * - [IOException] (connection/timeout) → [DeliveryResult.RetriableFailure]
 * - [InterruptedException] → [DeliveryResult.RetriableFailure] (interrupt flag restored at the
 *   synchronous call site that observed it; classification itself has no side effects)
 * - other (corrupt metadata, unknown service, malformed URI, illegal argument) → [DeliveryResult.PermanentFailure]
 *
 * [deliverBatch] fires all requests via [HttpClient.sendAsync] in parallel instead of blocking
 * sequentially on [HttpClient.send]. The JDK [HttpClient] will use HTTP/2 multiplexing when
 * supported by the server; otherwise it will fall back to HTTP/1.1 (potentially with multiple
 * connections) — either way, requests can be overlapped without explicit per-host grouping.
 */
class HttpMessageDeliverer @JvmOverloads constructor(
    private val urlResolver: ServiceUrlResolver,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val retriableStatusCodes: Set<Int> = DEFAULT_RETRIABLE_STATUS_CODES,
) : MessageDeliverer {
    override val type: String = HttpDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult = try {
        val request = buildRequest(entry)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        classifyResponse(response.statusCode(), response.body())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        classifyThrowable(e)
    } catch (e: Exception) {
        classifyThrowable(e)
    }

    /**
     * Fires all requests via [HttpClient.sendAsync] before awaiting any of them, so N requests
     * incur ~max(latency) instead of ~sum(latency). A synchronous failure building one entry's
     * request (corrupt metadata, unknown service) is isolated to that entry and does not prevent
     * the rest of the batch from firing.
     */
    override fun deliverBatch(entries: List<OutboxEntry>): List<DeliveryOutcome> {
        if (entries.isEmpty()) return emptyList()

        val attempts: List<Pair<OutboxEntry, SendAttempt>> = entries.map { entry -> entry to fireOne(entry) }

        return attempts.map { (entry, attempt) ->
            val result = when (attempt) {
                is SendAttempt.ImmediateFailure -> attempt.result
                is SendAttempt.InFlight -> awaitResult(attempt.future)
            }
            DeliveryOutcome(entry, result)
        }
    }

    /**
     * Awaits via [CompletableFuture.get] rather than [CompletableFuture.join] so that an
     * interrupt on the caller (processor thread shutdown/backpressure) is observed instead of
     * being ignored — the flag is restored here, on the interrupted thread itself, and the
     * remaining not-yet-awaited futures in the batch will then also fail fast as retriable.
     */
    private fun awaitResult(future: CompletableFuture<DeliveryResult>): DeliveryResult = try {
        future.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        classifyThrowable(e)
    }

    private fun fireOne(entry: OutboxEntry): SendAttempt = try {
        val request = buildRequest(entry)
        val future: CompletableFuture<DeliveryResult> = httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response -> classifyResponse(response.statusCode(), response.body()) }
            .exceptionally { e -> classifyThrowable(e.cause ?: e) }
        SendAttempt.InFlight(future)
    } catch (e: Exception) {
        SendAttempt.ImmediateFailure(classifyThrowable(e))
    }

    private fun buildRequest(entry: OutboxEntry): HttpRequest {
        val info = HttpDeliveryInfo.deserialize(entry.deliveryMetadata)
        val url = urlResolver.resolve(info.serviceName) + info.endpointPath

        return HttpRequest
            .newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .setHeader("Content-Type", "application/json")
            .method(
                info.httpMethod.name,
                HttpRequest.BodyPublishers.ofString(entry.payload),
            )
            .apply { info.headers.forEach { (k, v) -> setHeader(k, v) } }
            .build()
    }

    private fun classifyResponse(status: Int, body: String): DeliveryResult = when {
        status in 200..299 -> DeliveryResult.Success
        status in retriableStatusCodes -> DeliveryResult.RetriableFailure("HTTP $status: $body")
        else -> DeliveryResult.PermanentFailure("HTTP $status: $body")
    }

    private fun classifyThrowable(e: Throwable): DeliveryResult {
        val message = e.message ?: e.javaClass.simpleName
        return when (e) {
            // Subtypes of IOException, but neither fixes itself on retry — classify before IOException.
            is JsonProcessingException -> DeliveryResult.PermanentFailure(message)
            is SSLException -> DeliveryResult.PermanentFailure(message)
            is IOException -> DeliveryResult.RetriableFailure(message)
            // Deliberately side-effect free: this can run on an HttpClient completion thread
            // (via fireOne()'s .exceptionally), not the caller's thread, so interrupting
            // "current thread" here would interrupt an unrelated shared pool thread. Callers on
            // a genuinely interrupted thread (deliver(), awaitResult()) restore the flag themselves.
            is InterruptedException -> DeliveryResult.RetriableFailure(message)
            else -> DeliveryResult.PermanentFailure(message)
        }
    }

    private sealed interface SendAttempt {
        data class InFlight(val future: CompletableFuture<DeliveryResult>) : SendAttempt
        data class ImmediateFailure(val result: DeliveryResult) : SendAttempt
    }

    companion object {
        val DEFAULT_RETRIABLE_STATUS_CODES: Set<Int> = (500..599).toSet() + 429 + 408
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
