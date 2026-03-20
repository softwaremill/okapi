package com.softwaremill.okapi.core

/**
 * Executes a block of code within a transaction.
 *
 * Framework-specific modules provide implementations:
 * - `okapi-spring`: wraps Spring's `TransactionTemplate`
 * - `okapi-ktor`: wraps Exposed's `transaction {}`
 * - Standalone: user-provided lambda
 */
interface TransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}
