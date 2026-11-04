package com.softwaremill.okapi.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.softwaremill.okapi.core.DeliveryOutcome
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxHeaders
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * [MessageDeliverer] that sends outbox entries as HTTP requests via JDK [HttpClient].
 *
 * Every request carries [OutboxHeaders.OUTBOX_ID] for consumer-side deduplication; see
 * [buildRequest] for how it interacts with headers supplied through [HttpDeliveryInfo].
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
 *
 * Each exchange, response body included, must complete within [EXCHANGE_TIMEOUT]; otherwise it is
 * cancelled and classified as [DeliveryResult.RetriableFailure]. [HttpRequest.timeout] alone only
 * covers waiting for the response headers, so a server dripping a body could otherwise hold
 * delivery, and with it the scheduler's claim transaction, open indefinitely.
 */
class HttpMessageDeliverer internal constructor(
    private val urlResolver: ServiceUrlResolver,
    private val httpClient: HttpClient,
    private val retriableStatusCodes: Set<Int>,
    private val exchangeTimeout: Duration,
) : MessageDeliverer {
    @JvmOverloads constructor(
        urlResolver: ServiceUrlResolver,
        httpClient: HttpClient = defaultHttpClient(),
        retriableStatusCodes: Set<Int> = DEFAULT_RETRIABLE_STATUS_CODES,
    ) : this(urlResolver, httpClient, retriableStatusCodes, EXCHANGE_TIMEOUT)

    override val type: String = HttpDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult = when (val attempt = fireOne(entry)) {
        is SendAttempt.ImmediateFailure -> attempt.result
        is SendAttempt.InFlight -> awaitResult(attempt)
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
                is SendAttempt.InFlight -> awaitResult(attempt)
            }
            DeliveryOutcome(entry, result)
        }
    }

    /**
     * Awaits via [CompletableFuture.get] rather than [CompletableFuture.join] so that an
     * interrupt on the caller (processor thread shutdown/backpressure) is observed instead of
     * being ignored — the flag is restored here, on the interrupted thread itself, and the
     * remaining not-yet-awaited futures in the batch will then also fail fast as retriable. The
     * interrupted exchange itself is cancelled, as [HttpClient.send] would.
     */
    private fun awaitResult(attempt: SendAttempt.InFlight): DeliveryResult = try {
        attempt.result.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        attempt.exchange.cancel(true)
        classifyThrowable(e)
    } catch (e: Exception) {
        // fireOne()'s .exceptionally already converts every failure into a normal completion, so
        // get() should never throw ExecutionException/CancellationException in practice — but
        // deliverBatch's contract is MUST NOT throw, so this stays defensive.
        classifyThrowable(e.cause ?: e)
    }

    /**
     * The deadline runs on a copy of the exchange future: completing that copy with a
     * [TimeoutException] does not abort the HTTP exchange, so the exchange is cancelled explicitly.
     */
    private fun fireOne(entry: OutboxEntry): SendAttempt = try {
        val request = buildRequest(entry)
        val exchange = httpClient.sendAsync(request, boundedBodyHandler)
        val result: CompletableFuture<DeliveryResult> = exchange.copy()
            .orTimeout(exchangeTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle { response, failure ->
                if (failure == null) {
                    classifyResponse(response.statusCode(), response.body())
                } else {
                    val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                    if (cause is TimeoutException) exchange.cancel(true)
                    classifyThrowable(cause)
                }
            }
        SendAttempt.InFlight(exchange, result)
    } catch (e: Exception) {
        SendAttempt.ImmediateFailure(classifyThrowable(e))
    }

    /**
     * [OutboxHeaders.OUTBOX_ID] is set after the caller's own headers, so it wins over an
     * [HttpDeliveryInfo] header of the same name — `setHeader` replaces, so the caller's value is
     * discarded rather than merely shadowed (Kafka, whose headers are multi-valued, keeps both and
     * resolves by `lastHeader`).
     */
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
            .setHeader(OutboxHeaders.OUTBOX_ID, entry.outboxId.raw.toString())
            .build()
    }

    /**
     * Discards success bodies and keeps at most [MAX_ERROR_BODY_BYTES] of an error body (issue #130).
     * The body only ever feeds `lastError`, and `BodyHandlers.ofString()` buffered all of it in heap
     * -- an endpoint answering with a multi-megabyte error page cost that much memory per in-flight
     * request, and the full text then overflowed the `last_error` column. Either way at most
     * [MAX_DRAINED_BODY_BYTES] are read; see [BoundedUtf8BodySubscriber].
     */
    private val boundedBodyHandler = HttpResponse.BodyHandler { info ->
        val keep = if (info.statusCode() in 200..299) 0 else MAX_ERROR_BODY_BYTES
        BoundedUtf8BodySubscriber(keep, MAX_DRAINED_BODY_BYTES)
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
            is TimeoutException -> DeliveryResult.RetriableFailure("HTTP exchange did not complete within $exchangeTimeout")
            // Deliberately side-effect free: this can run on an HttpClient completion thread
            // (via fireOne()'s .exceptionally), not the caller's thread, so interrupting
            // "current thread" here would interrupt an unrelated shared pool thread. Callers on
            // a genuinely interrupted thread (deliver(), awaitResult()) restore the flag themselves.
            is InterruptedException -> DeliveryResult.RetriableFailure(message)
            else -> DeliveryResult.PermanentFailure(message)
        }
    }

    private sealed interface SendAttempt {
        /** [exchange] is the raw [HttpClient.sendAsync] future, kept so the exchange can be cancelled. */
        data class InFlight(val exchange: CompletableFuture<*>, val result: CompletableFuture<DeliveryResult>) : SendAttempt
        data class ImmediateFailure(val result: DeliveryResult) : SendAttempt
    }

    companion object {
        /** How much of an error response body is kept for `lastError`; the rest is dropped. */
        const val MAX_ERROR_BODY_BYTES: Int = 2048

        /**
         * How much of any response body is read. Up to this size the body is drained so the
         * connection can be reused; past it the body is cancelled, which closes an HTTP/1.1
         * connection (HTTP/2 resets only the stream).
         */
        const val MAX_DRAINED_BODY_BYTES: Long = 64 * 1024

        /** Upper bound on one exchange, from sending the request to the end of the response body. */
        val EXCHANGE_TIMEOUT: Duration = Duration.ofSeconds(30)

        val DEFAULT_RETRIABLE_STATUS_CODES: Set<Int> = (500..599).toSet() + 429 + 408
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}

/**
 * Keeps the first [keepLimit] bytes of a body as UTF-8 and reads at most [readLimit] bytes in total.
 * Below [readLimit] the rest is drained without being stored, so the connection stays reusable;
 * past it the subscription is cancelled rather than reading an arbitrarily large or endless body.
 * A multi-byte character cut at [keepLimit] decodes as U+FFFD, which is fine for an error message.
 *
 * The HTTP client signals this subscriber serially (Reactive Streams §1.3), so its state needs no
 * synchronization.
 */
private class BoundedUtf8BodySubscriber(
    private val keepLimit: Int,
    private val readLimit: Long,
) : HttpResponse.BodySubscriber<String> {
    private val body = CompletableFuture<String>()
    private val kept = ByteArrayOutputStream()
    private var read = 0L
    private var done = false
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<String> = body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (done) return
        for (buffer in item) {
            val take = minOf(keepLimit - kept.size(), buffer.remaining())
            if (take > 0) kept.write(ByteArray(take).also { buffer.get(it) })
            read += take + buffer.remaining()
        }
        if (read > readLimit) {
            subscription.cancel()
            finish()
        }
    }

    override fun onError(throwable: Throwable) {
        if (done) return
        done = true
        body.completeExceptionally(throwable)
    }

    override fun onComplete() {
        if (!done) finish()
    }

    private fun finish() {
        done = true
        val truncated = read > kept.size()
        body.complete(kept.toString(Charsets.UTF_8) + if (truncated && keepLimit > 0) OutboxEntry.TRUNCATION_MARKER else "")
    }
}
