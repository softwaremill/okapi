package com.softwaremill.okapi.exposed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedConnectionProviderTest : FunSpec({

    val outboxDb = Database.connect(
        "jdbc:h2:mem:exposed_provider_outbox_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    val otherDb = Database.connect(
        "jdbc:h2:mem:exposed_provider_other_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    val provider = ExposedConnectionProvider(outboxDb)

    test("throws IllegalStateException with actionable message when called outside an Exposed transaction") {
        val ex = shouldThrow<IllegalStateException> {
            provider.withConnection { /* unreachable */ }
        }
        ex.message shouldContain "ExposedConnectionProvider.withConnection"
        ex.message shouldContain "transaction(database) { }"
    }

    test("supplies the active Exposed transaction's connection to the block") {
        val connectionWasOpen: Boolean = transaction(outboxDb) {
            provider.withConnection { conn -> !conn.isClosed }
        }

        connectionWasOpen shouldBe true
    }

    test("throws when only a transaction on a DIFFERENT Database is active") {
        val ex = shouldThrow<IllegalStateException> {
            transaction(otherDb) {
                provider.withConnection { /* unreachable */ }
            }
        }
        ex.message shouldContain "ExposedConnectionProvider.withConnection"
    }

    test("resolves the outbox Database's transaction even when nested inside a transaction on another Database") {
        val connectionWasOpen: Boolean = transaction(otherDb) {
            transaction(outboxDb) {
                provider.withConnection { conn -> !conn.isClosed }
            }
        }

        connectionWasOpen shouldBe true
    }
})
