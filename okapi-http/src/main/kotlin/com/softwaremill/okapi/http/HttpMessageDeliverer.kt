package com.softwaremill.okapi.http

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpMessageDeliverer(
    private val urlResolver: ServiceUrlResolver,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : MessageDeliverer {
    override val type: String = HttpDeliveryInfo.TYPE

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        val info = HttpDeliveryInfo.deserialize(entry.deliveryMetadata)
        val url = urlResolver.resolve(info.serviceName) + info.endpointPath

        return try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .method(
                        info.httpMethod.name,
                        HttpRequest.BodyPublishers.ofString(entry.payload),
                    )
                    .apply { info.headers.forEach { (k, v) -> header(k, v) } }
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            when (response.statusCode()) {
                in 200..299 -> DeliveryResult.Success
                in 500..599 -> DeliveryResult.RetriableFailure("HTTP ${response.statusCode()}: ${response.body()}")
                else -> DeliveryResult.PermanentFailure("HTTP ${response.statusCode()}: ${response.body()}")
            }
        } catch (e: Exception) {
            DeliveryResult.RetriableFailure(e.message ?: "Connection failed")
        }
    }
}
