package com.softwaremill.okapi.core

import java.util.UUID

@JvmInline
value class OutboxId(val raw: UUID) {
    companion object {
        fun new(): OutboxId = OutboxId(UUID.randomUUID())
    }
}
