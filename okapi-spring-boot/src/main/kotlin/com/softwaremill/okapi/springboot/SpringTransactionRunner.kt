package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * Spring implementation of [TransactionRunner] using [TransactionTemplate].
 */
class SpringTransactionRunner(
    // internal (not public): same-module tests assert reference identity; publishing the field
    // would freeze "implemented via TransactionTemplate" as part of the library's API contract.
    internal val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transactionTemplate.execute { block() }!!
}
