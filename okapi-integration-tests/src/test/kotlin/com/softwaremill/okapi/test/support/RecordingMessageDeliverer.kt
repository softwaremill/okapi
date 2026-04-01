package com.softwaremill.okapi.test.support

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DeliveryRecord(
    val entry: OutboxEntry,
    val threadName: String,
    val timestamp: Instant,
)

class RecordingMessageDeliverer(
    private val resultProvider: (OutboxEntry) -> DeliveryResult = { DeliveryResult.Success },
) : MessageDeliverer {
    override val type: String = "recording"

    private val _deliveries = ConcurrentHashMap<OutboxId, MutableList<DeliveryRecord>>()

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        _deliveries.computeIfAbsent(entry.outboxId) {
            Collections.synchronizedList(mutableListOf())
        }.add(DeliveryRecord(entry, Thread.currentThread().name, Instant.now()))
        return resultProvider(entry)
    }

    val deliveries: Map<OutboxId, List<DeliveryRecord>> get() = _deliveries.toMap()

    fun deliveryCount(): Int = _deliveries.size

    fun assertNoAmplification() {
        val amplified = _deliveries.filter { (_, records) -> records.size > 1 }
        check(amplified.isEmpty()) {
            val details = amplified.entries.joinToString("\n") { (id, records) ->
                "  $id delivered ${records.size} times by: ${records.map { it.threadName }}"
            }
            "Delivery amplification detected:\n$details"
        }
    }
}
