package com.softwaremill.okapi.postgres

import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json

internal object OutboxTable : Table("outbox") {
    val id = uuid("id").transform(wrap = { OutboxId(it) }, unwrap = { it.raw })
    val messageType = varchar("message_type", 255)
    val payload = text("payload")
    val deliveryType = varchar("delivery_type", 50)
    val status = varchar("status", 50).default(OutboxStatus.PENDING.name)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val retries = integer("retries").default(0)
    val lastAttempt = timestamp("last_attempt").nullable()
    val lastError = text("last_error").nullable()
    val deliveryMetadata = json("delivery_metadata", { it }, { it })

    override val primaryKey = PrimaryKey(id)
}
