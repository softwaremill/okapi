package com.softwaremill.okapi.core

/** Business message to be delivered via the transactional outbox. */
data class OutboxMessage(
    val messageType: String,
    val payload: String,
)
