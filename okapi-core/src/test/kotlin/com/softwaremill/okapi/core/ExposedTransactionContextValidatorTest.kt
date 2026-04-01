package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedTransactionContextValidatorTest : BehaviorSpec({

    val outboxDb = Database.connect(
        "jdbc:h2:mem:outbox_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    val otherDb = Database.connect(
        "jdbc:h2:mem:other_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )
    val validator = ExposedTransactionContextValidator(outboxDb)

    given("no active Exposed transaction") {
        then("returns false") {
            validator.isInActiveReadWriteTransaction() shouldBe false
        }
    }

    given("active transaction on outbox Database") {
        then("returns true") {
            transaction(outboxDb) {
                validator.isInActiveReadWriteTransaction() shouldBe true
            }
        }
    }

    given("active transaction on OTHER Database") {
        then("returns false") {
            transaction(otherDb) {
                validator.isInActiveReadWriteTransaction() shouldBe false
            }
        }
    }

    given("nested transaction: outboxDb outer, otherDb inner") {
        then("returns true — outboxDb transaction is still active on the stack") {
            transaction(outboxDb) {
                transaction(otherDb) {
                    validator.isInActiveReadWriteTransaction() shouldBe true
                }
            }
        }
    }

    given("nested transaction: otherDb outer, outboxDb inner") {
        then("returns true inside inner transaction") {
            transaction(otherDb) {
                transaction(outboxDb) {
                    validator.isInActiveReadWriteTransaction() shouldBe true
                }
            }
        }
    }

    given("read-only transaction on outbox Database") {
        then("returns false") {
            transaction(db = outboxDb, readOnly = true) {
                validator.isInActiveReadWriteTransaction() shouldBe false
            }
        }
    }
})
