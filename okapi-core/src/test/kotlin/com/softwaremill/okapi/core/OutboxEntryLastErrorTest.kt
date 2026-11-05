package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant

class OutboxEntryLastErrorTest : FunSpec({
    val info = object : DeliveryInfo {
        override val type = "stub"
        override fun serialize(): String = """{"type":"stub"}"""
    }
    val entry = OutboxEntry.createPending(OutboxMessage("test.event", "{}"), info, Instant.EPOCH)
    val now = Instant.parse("2024-01-01T00:00:00Z")

    test("a short error is stored unchanged") {
        entry.retry(now, "HTTP 503: unavailable").lastError shouldBe "HTTP 503: unavailable"
        entry.toFailed(now, "HTTP 400: bad").lastError shouldBe "HTTP 400: bad"
    }

    test("an over-long error is truncated to MAX_LAST_ERROR_LENGTH with a marker, on retry and on failure") {
        val huge = "HTTP 503: " + "x".repeat(100_000)
        listOf(entry.retry(now, huge), entry.toFailed(now, huge)).forEach { updated ->
            val stored = checkNotNull(updated.lastError)
            stored.length shouldBeLessThanOrEqual OutboxEntry.MAX_LAST_ERROR_LENGTH
            stored shouldStartWith "HTTP 503: xxx"
            stored shouldEndWith OutboxEntry.TRUNCATION_MARKER
        }
    }

    test("an error of exactly MAX_LAST_ERROR_LENGTH is not truncated") {
        val exact = "y".repeat(OutboxEntry.MAX_LAST_ERROR_LENGTH)
        entry.retry(now, exact).lastError shouldBe exact
    }

    test("NUL characters are removed") {
        entry.retry(now, "binary\u0000body\u0000").lastError shouldBe "binarybody"
    }

    test("truncation never splits a surrogate pair") {
        // Pad so the cut lands right after the high surrogate of an emoji.
        val cut = OutboxEntry.MAX_LAST_ERROR_LENGTH - OutboxEntry.TRUNCATION_MARKER.length
        val error = "a".repeat(cut - 1) + "😀" + "b".repeat(10_000)
        val stored = checkNotNull(entry.retry(now, error).lastError)
        stored.toCharArray().forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) Character.isLowSurrogate(stored.getOrElse(i + 1) { ' ' }) shouldBe true
        }
        stored shouldNotContain "\uD83D..."
    }
})
