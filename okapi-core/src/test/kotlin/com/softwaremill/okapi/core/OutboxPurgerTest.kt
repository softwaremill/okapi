package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val fixedNow = Instant.parse("2025-03-20T12:00:00Z")
private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

class OutboxPurgerTest : FunSpec({
    test("tick removes entries older than retention duration") {
        var capturedCutoff: Instant? = null
        val store = object : OutboxStore {
            override fun persist(entry: OutboxEntry) = entry
            override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
            override fun updateAfterProcessing(entry: OutboxEntry) = entry
            override fun removeDeliveredBefore(time: Instant) {
                capturedCutoff = time
            }
            override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
            override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
        }

        val purger = OutboxPurger(
            outboxStore = store,
            retentionDuration = Duration.ofDays(7),
            intervalMs = 50,
            clock = fixedClock,
        )

        purger.start()
        Thread.sleep(150)
        purger.stop()

        capturedCutoff shouldBe fixedNow.minus(Duration.ofDays(7))
    }
})
