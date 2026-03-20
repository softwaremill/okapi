package com.softwaremill.okapi.core

enum class OutboxStatus {
    PENDING,
    DELIVERED,
    FAILED,
    ;

    companion object {
        fun from(value: String): OutboxStatus = requireNotNull(entries.find { it.name == value }) {
            "Unknown outbox status: $value"
        }
    }
}
