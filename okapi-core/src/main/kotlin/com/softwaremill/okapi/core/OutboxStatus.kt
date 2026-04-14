package com.softwaremill.okapi.core

/** Lifecycle status of an [OutboxEntry]. */
enum class OutboxStatus {
    PENDING,
    DELIVERED,
    FAILED,
    ;

    companion object {
        /** Resolves a status by matching the given [value] against enum entry names. Throws if unknown. */
        @JvmStatic
        fun from(value: String): OutboxStatus = requireNotNull(entries.find { it.name == value }) {
            "Unknown outbox status: $value"
        }
    }
}
