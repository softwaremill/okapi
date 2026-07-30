package com.softwaremill.okapi.springboot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
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

    // The outbox DataSource bean is a TransactionAwareDataSourceProxy (Spring's own
    // documented pattern -- "TransactionAwareDataSourceProxy should NOT be passed to a PTM"), so
    // the PlatformTransactionManager binds its resource under the RAW target, never the proxy.
    // Before this fix, the validator compared against the raw `dataSource` constructor argument
    // (the proxy here) with no unwrapping, so getResource(proxy) always returned null and
    // publish() failed on every call despite OutboxAutoConfiguration's startup check having
    // already verified this exact wiring as correct.
    given("outbox DataSource bean is a TransactionAwareDataSourceProxy wrapping the PTM's raw target") {
        then("an active RW transaction with the resource bound to the RAW target is recognised") {
            val rawDs: DataSource = SimpleDriverDataSource()
            val proxyDs: DataSource = TransactionAwareDataSourceProxy(rawDs)
            val proxyValidator = SpringTransactionContextValidator(proxyDs)

            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(rawDs, Any())
            try {
                proxyValidator.isInActiveReadWriteTransaction() shouldBe true
            } finally {
                TransactionSynchronizationManager.unbindResource(rawDs)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }

        then("a nested chain (TADP -> LazyConnectionDataSourceProxy -> raw) also resolves to the raw target") {
            val rawDs: DataSource = SimpleDriverDataSource()
            val nested: DataSource = TransactionAwareDataSourceProxy(LazyConnectionDataSourceProxy(rawDs))
            val nestedValidator = SpringTransactionContextValidator(nested)

            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(rawDs, Any())
            try {
                nestedValidator.isInActiveReadWriteTransaction() shouldBe true
            } finally {
                TransactionSynchronizationManager.unbindResource(rawDs)
                TransactionSynchronizationManager.clearSynchronization()
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
        }
    }

    given("outbox DataSource bean is an unresolvable DelegatingDataSource chain") {
        then("construction fails fast with an actionable message, instead of every later publish() silently failing validation") {
            val cyclic = LazyConnectionDataSourceProxy()
            cyclic.setTargetDataSource(cyclic)

            val exception = shouldThrow<IllegalStateException> {
                SpringTransactionContextValidator(cyclic)
            }
            exception.message shouldContain "Could not resolve the outbox DataSource"
        }
    }
})
