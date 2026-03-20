package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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

class OutboxPublisherTest :
    BehaviorSpec({
        val capturedEntries = mutableListOf<OutboxEntry>()

        val outboxStore =
            object : OutboxStore {
                override fun persist(entry: OutboxEntry): OutboxEntry = entry.also { capturedEntries += it }

                override fun claimPending(limit: Int) = emptyList<OutboxEntry>()

                override fun updateAfterProcessing(entry: OutboxEntry) = entry

                override fun removeDeliveredBefore(time: Instant) = Unit

                override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()

                override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
            }

        val publisher = OutboxPublisher(outboxStore, fixedClock)

        beforeEach {
            capturedEntries.clear()
        }

        given("publish()") {
            `when`("called with valid message and delivery info") {
                val outboxId = publisher.publish(testMessage, stubDeliveryInfo)
                val snapshot = capturedEntries.toList()

                then("returns OutboxId") {
                    snapshot.first().outboxId shouldBe outboxId
                }
                then("persists entry with PENDING status") {
                    snapshot.size shouldBe 1
                    snapshot.first().status shouldBe OutboxStatus.PENDING
                }
                then("entry deliveryMetadata matches serialized DeliveryInfo") {
                    snapshot.first().deliveryMetadata shouldBe stubDeliveryInfo.serialize()
                }
                then("entry timestamps use provided clock") {
                    snapshot.first().createdAt shouldBe fixedClock.instant()
                    snapshot.first().updatedAt shouldBe fixedClock.instant()
                }
            }
        }
    })
