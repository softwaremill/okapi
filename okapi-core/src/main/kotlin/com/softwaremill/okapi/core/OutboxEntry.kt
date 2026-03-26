package com.softwaremill.okapi.core

import java.time.Instant

/**
 * Persistent representation of an outbox message with delivery state.
 *
 * Created via [createPending] and progressed through [retry], [toDelivered],
 * or [toFailed] — each returning a new immutable copy.
 */
data class OutboxEntry(
    val outboxId: OutboxId,
    val messageType: String,
    val payload: String,
    val deliveryType: String,
    val status: OutboxStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val retries: Int,
    val lastAttempt: Instant?,
    val lastError: String?,
    val deliveryMetadata: String,
) {
    /** Returns a copy scheduled for another delivery attempt. */
    fun retry(now: Instant, lastError: String): OutboxEntry = copy(
        status = OutboxStatus.PENDING,
        updatedAt = now,
        retries = retries + 1,
        lastAttempt = now,
        lastError = lastError,
    )

    /** Returns a copy marked as permanently failed. */
    fun toFailed(now: Instant, lastError: String): OutboxEntry = copy(
        status = OutboxStatus.FAILED,
        updatedAt = now,
        lastAttempt = now,
        lastError = lastError,
    )

    /** Returns a copy marked as successfully delivered. */
    fun toDelivered(now: Instant): OutboxEntry = copy(
        status = OutboxStatus.DELIVERED,
        updatedAt = now,
        lastAttempt = now,
    )

    companion object {
        /** Creates a new PENDING entry from a [message] and [deliveryInfo]. */
        fun createPending(message: OutboxMessage, deliveryInfo: DeliveryInfo, now: Instant): OutboxEntry =
            createPending(message, deliveryType = deliveryInfo.type, deliveryMetadata = deliveryInfo.serialize(), now = now)

        internal fun createPending(message: OutboxMessage, deliveryType: String, deliveryMetadata: String, now: Instant): OutboxEntry =
            OutboxEntry(
                outboxId = OutboxId.new(),
                messageType = message.messageType,
                payload = message.payload,
                deliveryType = deliveryType,
                status = OutboxStatus.PENDING,
                createdAt = now,
                updatedAt = now,
                retries = 0,
                lastAttempt = null,
                lastError = null,
                deliveryMetadata = deliveryMetadata,
            )
    }
}
