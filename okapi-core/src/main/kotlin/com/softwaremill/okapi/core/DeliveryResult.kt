package com.softwaremill.okapi.core

sealed interface DeliveryResult {
    data object Success : DeliveryResult

    data class RetriableFailure(val error: String) : DeliveryResult

    data class PermanentFailure(val error: String) : DeliveryResult
}
