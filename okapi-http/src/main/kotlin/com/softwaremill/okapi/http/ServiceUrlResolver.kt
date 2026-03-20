package com.softwaremill.okapi.http

/**
 * Resolves a logical service name to a base URL.
 *
 * Example implementations: static map, service registry (Consul, Eureka), environment config.
 */
fun interface ServiceUrlResolver {
    fun resolve(serviceName: String): String
}
