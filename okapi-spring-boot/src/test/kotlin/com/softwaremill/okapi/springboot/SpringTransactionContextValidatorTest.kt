package com.softwaremill.okapi.springboot

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class SpringTransactionContextValidatorTest : BehaviorSpec({

    val outboxDataSource: DataSource = SimpleDriverDataSource()
    val otherDataSource: DataSource = SimpleDriverDataSource()
    val validator = SpringTransactionContextValidator(outboxDataSource)

    beforeEach {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        if (TransactionSynchronizationManager.hasResource(outboxDataSource)) {
            TransactionSynchronizationManager.unbindResource(outboxDataSource)
        }
        if (TransactionSynchronizationManager.hasResource(otherDataSource)) {
            TransactionSynchronizationManager.unbindResource(otherDataSource)
        }
    }

    given("no active transaction") {
        then("returns false") {
            validator.isInActiveReadWriteTransaction() shouldBe false
        }
    }

    given("active RW transaction with outbox DataSource resource bound") {
        then("returns true") {
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(outboxDataSource, Any())
            try {
                validator.isInActiveReadWriteTransaction() shouldBe true
            } finally {
                TransactionSynchronizationManager.unbindResource(outboxDataSource)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
    }

    given("active RW transaction but resource bound to OTHER DataSource") {
        then("returns false") {
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(otherDataSource, Any())
            try {
                validator.isInActiveReadWriteTransaction() shouldBe false
            } finally {
                TransactionSynchronizationManager.unbindResource(otherDataSource)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
    }

    given("active read-only transaction with outbox DataSource resource bound") {
        then("returns false") {
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
            TransactionSynchronizationManager.bindResource(outboxDataSource, Any())
            try {
                validator.isInActiveReadWriteTransaction() shouldBe false
            } finally {
                TransactionSynchronizationManager.unbindResource(outboxDataSource)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
            }
        }
    }

    given("active RW transaction with BOTH DataSources bound") {
        then("returns true") {
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(outboxDataSource, Any())
            TransactionSynchronizationManager.bindResource(otherDataSource, Any())
            try {
                validator.isInActiveReadWriteTransaction() shouldBe true
            } finally {
                TransactionSynchronizationManager.unbindResource(outboxDataSource)
                TransactionSynchronizationManager.unbindResource(otherDataSource)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
    }
})
