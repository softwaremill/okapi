package com.softwaremill.okapi.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [MessageDeliverer] that sends outbox entries as HTTP requests via JDK [HttpClient].
 *
 * Status code classification:
 * - 2xx → [DeliveryResult.Success]
 * - 5xx, 429, 408 → [DeliveryResult.RetriableFailure] (configurable via [retriableStatusCodes])
 * - other → [DeliveryResult.PermanentFailure]
 *
 * Exception classification:
 * - [IOException] (connection/timeout) → [DeliveryResult.RetriableFailure]
 * - [InterruptedException] → [DeliveryResult.RetriableFailure] (interrupt flag restored)
 * - other (corrupt metadata, unknown service, malformed URI) → [DeliveryResult.PermanentFailure]
 */
class HttpMessageDeliverer @JvmOverloads constructor(
    private val urlResolver: ServiceUrlResolver,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val retriableStatusCodes: Set<Int> = DEFAULT_RETRIABLE_STATUS_CODES,
) : MessageDeliverer {
    override val type: String = HttpDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult = try {
        val info = HttpDeliveryInfo.deserialize(entry.deliveryMetadata)
        val url = urlResolver.resolve(info.serviceName) + info.endpointPath

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(
                    info.httpMethod.name,
                    HttpRequest.BodyPublishers.ofString(entry.payload),
                )
                .apply { info.headers.forEach { (k, v) -> header(k, v) } }
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        when {
            status in 200..299 -> DeliveryResult.Success
            status in retriableStatusCodes -> DeliveryResult.RetriableFailure("HTTP $status: $body")
            else -> DeliveryResult.PermanentFailure("HTTP $status: $body")
        }
    } catch (e: JsonProcessingException) {
        // Subtype of IOException, but corrupt metadata won't fix itself — classify before IOException.
        DeliveryResult.PermanentFailure(e.message ?: e.javaClass.simpleName)
    } catch (e: IOException) {
        DeliveryResult.RetriableFailure(e.message ?: "Connection failed")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        DeliveryResult.RetriableFailure(e.message ?: "Interrupted")
    } catch (e: Exception) {
        DeliveryResult.PermanentFailure(e.message ?: e.javaClass.simpleName)
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
