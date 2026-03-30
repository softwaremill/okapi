package com.softwaremill.okapi.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.softwaremill.okapi.core.DeliveryInfo

/**
 * Delivery metadata for HTTP webhook transport.
 *
 * [serviceName] is resolved to a base URL via [ServiceUrlResolver] at delivery time.
 * [endpointPath] is appended to form the full URL.
 */
data class HttpDeliveryInfo(
    override val type: String = TYPE,
    val serviceName: String,
    val endpointPath: String,
    val httpMethod: HttpMethod,
    val headers: Map<String, String> = emptyMap(),
) : DeliveryInfo {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(endpointPath.isNotBlank()) { "endpointPath must not be blank" }
    }

    override fun serialize(): String = mapper.writeValueAsString(this)

    companion object {
        const val TYPE = "http"
        private val mapper = jacksonObjectMapper()

        /** Deserializes from JSON stored in [OutboxEntry.deliveryMetadata]. */
        fun deserialize(json: String): HttpDeliveryInfo = mapper.readValue(json)
    }
}
