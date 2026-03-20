package com.softwaremill.okapi.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TransactionRunnerTest : BehaviorSpec({
    given("a passthrough TransactionRunner") {
        val runner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = block()
        }

        `when`("runInTransaction is called") {
            val result = runner.runInTransaction { 42 }

            then("returns the block result") {
                result shouldBe 42
            }
        }
    }

    given("a wrapping TransactionRunner") {
        val callLog = mutableListOf<String>()
        val runner = object : TransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T {
                callLog += "before"
                val result = block()
                callLog += "after"
                return result
            }
        }

        `when`("runInTransaction is called") {
            callLog.clear()
            runner.runInTransaction { callLog += "inside" }

            then("block executes between before and after") {
                callLog shouldBe listOf("before", "inside", "after")
            }
        }
    }
})
