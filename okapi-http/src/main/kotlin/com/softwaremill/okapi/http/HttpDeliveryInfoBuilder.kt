package com.softwaremill.okapi.http

@DslMarker
internal annotation class HttpDeliveryInfoDsl

@HttpDeliveryInfoDsl
class HttpDeliveryInfoBuilder {
    var serviceName: String = ""
    var endpointPath: String = ""
    var httpMethod: HttpMethod = HttpMethod.POST
    private val headers: MutableMap<String, String> = mutableMapOf()

    fun header(key: String, value: String) {
        headers[key] = value
    }

    fun build(): HttpDeliveryInfo = HttpDeliveryInfo(
        serviceName = serviceName,
        endpointPath = endpointPath,
        httpMethod = httpMethod,
        headers = headers.toMap(),
    )
}

/**
 * DSL for building [HttpDeliveryInfo].
 *
 * ```
 * val info = httpDeliveryInfo {
 *     serviceName = "order-service"
 *     endpointPath = "/api/events"
 *     httpMethod = HttpMethod.POST
 *     header("X-Correlation-Id", correlationId)
 * }
 * ```
 */
fun httpDeliveryInfo(block: HttpDeliveryInfoBuilder.() -> Unit): HttpDeliveryInfo = HttpDeliveryInfoBuilder().apply(block).build()
