package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * Spring implementation of [TransactionRunner] using [TransactionTemplate].
 */
class SpringTransactionRunner(
    internal val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T> runInTransaction(block: () -> T): T = transactionTemplate.execute { block() }!!
}
