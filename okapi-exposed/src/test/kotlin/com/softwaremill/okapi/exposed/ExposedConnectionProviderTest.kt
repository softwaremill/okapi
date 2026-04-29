package com.softwaremill.okapi.exposed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedConnectionProviderTest : FunSpec({

    val provider = ExposedConnectionProvider()

    test("throws IllegalStateException with actionable message when called outside an Exposed transaction") {
        val ex = shouldThrow<IllegalStateException> {
            provider.withConnection { /* unreachable */ }
        }
        ex.message shouldContain "ExposedConnectionProvider.withConnection"
        ex.message shouldContain "Exposed transaction { } block"
    }

    test("supplies the active Exposed transaction's connection to the block") {
        val db = Database.connect(
            "jdbc:h2:mem:exposed_provider_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

        val connectionWasOpen: Boolean = transaction(db) {
            provider.withConnection { conn -> !conn.isClosed }
        }

        connectionWasOpen shouldBe true
    }
})
