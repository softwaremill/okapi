package com.softwaremill.okapi.core

/**
 * Executes a block of code within a transaction.
 *
 * Framework-specific modules provide implementations:
 * - `okapi-spring-boot`: wraps Spring's `TransactionTemplate` via `SpringTransactionRunner`
 * - `okapi-exposed`: wraps Exposed's `transaction {}` via `ExposedTransactionRunner`
 * - Standalone: user-provided lambda
 *
 * Contract: when [runInTransaction] returns normally, the transaction it ran [block] in has
 * committed. [OutboxScheduler] relies on this to emit listener events only for committed state
 * (issue #137). A runner that joins an outer transaction instead (e.g. Spring's `REQUIRED` called
 * inside `@Transactional`, or a nested Exposed `transaction {}` without nested transactions) breaks
 * that. A runner may run [block] more than once, as Exposed does when a statement or the commit
 * throws an `SQLException`; only the result of the attempt that committed is returned.
 */
interface TransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}
