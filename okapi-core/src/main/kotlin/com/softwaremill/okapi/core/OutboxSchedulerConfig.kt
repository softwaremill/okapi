package com.softwaremill.okapi.core

data class OutboxSchedulerConfig(
    val intervalMs: Long = 1_000L,
    val batchSize: Int = 10,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive, got: $intervalMs" }
        require(batchSize > 0) { "batchSize must be positive, got: $batchSize" }
    }
}
