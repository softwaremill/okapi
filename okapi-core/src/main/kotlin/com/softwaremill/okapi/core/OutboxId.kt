package com.softwaremill.okapi.core

import java.util.UUID

/** Unique identifier for an outbox entry. Wraps a [UUID] for type safety. */
@JvmInline
value class OutboxId(val raw: UUID) {
    companion object {
        /** Generates a new random identifier. */
        fun new(): OutboxId = OutboxId(UUID.randomUUID())
    }
}
