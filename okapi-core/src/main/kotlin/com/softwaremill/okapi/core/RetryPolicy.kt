package com.softwaremill.okapi.core

data class RetryPolicy(val maxRetries: Int) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, got: $maxRetries" }
    }

    fun shouldRetry(currentRetries: Int): Boolean = currentRetries < maxRetries
}
