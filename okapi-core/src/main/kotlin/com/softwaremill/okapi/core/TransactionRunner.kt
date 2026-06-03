package com.softwaremill.okapi.core

/**
 * Executes a block of code within a transaction.
 *
 * Framework-specific modules provide implementations:
 * - `okapi-spring-boot`: wraps Spring's `TransactionTemplate` via `SpringTransactionRunner`
 * - `okapi-exposed`: wraps Exposed's `transaction {}` via `ExposedTransactionRunner`
 * - Standalone: user-provided lambda
 */
interface TransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}
