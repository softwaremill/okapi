package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private fun validatorReturning(value: Boolean) = object : TransactionContextValidator {
    override fun isInActiveReadWriteTransaction(): Boolean = value
}

class TransactionContextValidatorTest : BehaviorSpec({
    given("a validator indicating active read-write transaction") {
        val validator = validatorReturning(true)

        `when`("checked") {
            then("returns true") {
                validator.isInActiveReadWriteTransaction() shouldBe true
            }
        }
    }

    given("a validator indicating no active transaction") {
        val validator = validatorReturning(false)

        `when`("checked") {
            then("returns false") {
                validator.isInActiveReadWriteTransaction() shouldBe false
            }
        }
    }
})
