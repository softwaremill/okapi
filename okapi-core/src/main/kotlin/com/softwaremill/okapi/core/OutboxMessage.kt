package com.softwaremill.okapi.core

data class OutboxMessage(
    val messageType: String,
    val payload: String,
)
