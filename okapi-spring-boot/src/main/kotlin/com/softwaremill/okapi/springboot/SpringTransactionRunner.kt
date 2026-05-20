package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * Spring implementation of [TransactionRunner] using [TransactionTemplate].
 */
class SpringTransactionRunner(
    // Visible only to same-module tests asserting reference identity with a user-supplied TT —
    // public would freeze "implementation via TransactionTemplate" into the library's published
    // API. Cross-module tests (e.g. okapi-integration-tests) must verify behaviour, not internals.
    internal val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transactionTemplate.execute { block() }!!
}
