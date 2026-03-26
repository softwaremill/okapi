package com.softwaremill.okapi.core

/** Determines how many delivery attempts are allowed before an entry is marked as [OutboxStatus.FAILED]. */
data class RetryPolicy(val maxRetries: Int) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, got: $maxRetries" }
    }

    /** Returns `true` if [currentRetries] has not yet reached [maxRetries]. */
    fun shouldRetry(currentRetries: Int): Boolean = currentRetries < maxRetries
}
