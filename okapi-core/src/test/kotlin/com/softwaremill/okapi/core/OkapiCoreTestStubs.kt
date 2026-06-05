package com.softwaremill.okapi.core

/**
 * Shared no-op test doubles for okapi-core. A single source means a [TransactionRunner] contract
 * change is a one-line edit, not a sweep across every scheduler/purger test.
 */

internal fun noOpTransactionRunner() = object : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = block()
}
