package com.softwaremill.okapi.core

import java.time.Instant

/**
 * Persistent representation of an outbox message with delivery state.
 *
 * Created via [createPending] and progressed through [retry], [toDelivered],
 * or [toFailed] — each returning a new immutable copy.
 */
data class OutboxEntry(
    @get:JvmName("getOutboxId")
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
        lastError = sanitizeLastError(lastError),
    )

    /** Returns a copy marked as permanently failed. */
    fun toFailed(now: Instant, lastError: String): OutboxEntry = copy(
        status = OutboxStatus.FAILED,
        updatedAt = now,
        lastAttempt = now,
        lastError = sanitizeLastError(lastError),
    )

    /** Returns a copy marked as successfully delivered. */
    fun toDelivered(now: Instant): OutboxEntry = copy(
        status = OutboxStatus.DELIVERED,
        updatedAt = now,
        lastAttempt = now,
    )

    companion object {
        /**
         * Upper bound, in UTF-16 chars, on the [lastError] that [retry] and [toFailed] store. At most
         * 4 bytes per char in UTF-8, it stays well inside MySQL's 65,535-byte `TEXT` column.
         */
        const val MAX_LAST_ERROR_LENGTH: Int = 4096

        private const val TRUNCATION_MARKER = "... [truncated]"

        /**
         * Makes [error] safe to persist (issue #130). Every result of a batch is written in one
         * update inside the claim transaction, so a single value the database rejects rolls back the
         * whole batch -- including entries that were already delivered -- without advancing
         * `retries`, and the next tick redelivers all of them, forever. Two such values are cheap to
         * produce: an over-long one (an HTTP error page in the response body overflows MySQL's
         * `TEXT`) and one containing NUL, which Postgres refuses in text. NULs are dropped and the
         * rest is cut to [MAX_LAST_ERROR_LENGTH], never between the two halves of a surrogate pair,
         * which MySQL would reject as an invalid string.
         */
        internal fun sanitizeLastError(error: String): String {
            val withoutNul = if (error.indexOf('\u0000') >= 0) error.replace("\u0000", "") else error
            if (withoutNul.length <= MAX_LAST_ERROR_LENGTH) return withoutNul
            var end = MAX_LAST_ERROR_LENGTH - TRUNCATION_MARKER.length
            if (Character.isHighSurrogate(withoutNul[end - 1])) end--
            return withoutNul.substring(0, end) + TRUNCATION_MARKER
        }

        /** Creates a new PENDING entry from a [message] and [deliveryInfo]. */
        @JvmStatic
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
