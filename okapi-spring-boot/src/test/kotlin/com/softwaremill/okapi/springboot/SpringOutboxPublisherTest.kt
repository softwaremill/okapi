package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
private val stubDeliveryInfo =
    object : DeliveryInfo {
        override val type = "stub"

        override fun serialize(): String = """{"type":"stub"}"""
    }
private val testMessage = OutboxMessage(messageType = "test.event", payload = """{"key":"value"}""")

class SpringOutboxPublisherTest :
    BehaviorSpec({
        val capturedEntries = mutableListOf<OutboxEntry>()

        val outboxStore =
            object : OutboxStore {
                override fun persist(entry: OutboxEntry): OutboxEntry = entry.also { capturedEntries += it }

                override fun claimPending(limit: Int) = emptyList<OutboxEntry>()

                override fun updateAfterProcessing(entry: OutboxEntry) = entry

                override fun removeDeliveredBefore(time: Instant, limit: Int): Int = 0

                override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()

                override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
            }

        val corePublisher = OutboxPublisher(outboxStore, fixedClock)
        val publisher = SpringOutboxPublisher(corePublisher)

        beforeEach {
            capturedEntries.clear()
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization()
            }
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        }

        given("publish() with active read-write transaction") {
            `when`("called") {
                TransactionSynchronizationManager.initSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(true)

                val outboxId = publisher.publish(testMessage, stubDeliveryInfo)
                val snapshot = capturedEntries.toList()

                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)

                then("returns OutboxId") {
                    outboxId shouldNotBe null
                }
                then("persists entry with PENDING status") {
                    snapshot.size shouldBe 1
                    snapshot.first().status shouldBe OutboxStatus.PENDING
                }
                then("entry deliveryMetadata matches serialized DeliveryInfo") {
                    snapshot.first().deliveryMetadata shouldBe stubDeliveryInfo.serialize()
                }
            }
        }

        given("publish() with no active transaction") {
            `when`("called") {
                then("throws IllegalStateException") {
                    shouldThrow<IllegalStateException> {
                        publisher.publish(testMessage, stubDeliveryInfo)
                    }
                }
                then("nothing is persisted") {
                    capturedEntries.size shouldBe 0
                }
            }
        }

        given("publish() with read-only transaction") {
            `when`("called") {
                then("throws IllegalStateException") {
                    TransactionSynchronizationManager.initSynchronization()
                    TransactionSynchronizationManager.setActualTransactionActive(true)
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

                    try {
                        shouldThrow<IllegalStateException> {
                            publisher.publish(testMessage, stubDeliveryInfo)
                        }
                    } finally {
                        TransactionSynchronizationManager.clearSynchronization()
                        TransactionSynchronizationManager.setActualTransactionActive(false)
                        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
                    }
                }
                then("nothing is persisted") {
                    capturedEntries.size shouldBe 0
                }
            }
        }
    })
