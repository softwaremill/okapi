package com.softwaremill.okapi.core

/**
 * Outcome of a single delivery attempt by a [MessageDeliverer].
 *
 * [OutboxEntryProcessor] uses this to decide whether to retry, mark as failed,
 * or mark as delivered.
 */
sealed interface DeliveryResult {
    data object Success : DeliveryResult

    data class RetriableFailure(val error: String) : DeliveryResult

    data class PermanentFailure(val error: String) : DeliveryResult
}
