package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Two behavioural contracts of `okapiTransactionRunner` when Spring Boot's
 * `TransactionAutoConfiguration` has registered a `TransactionTemplate` bean for the single PTM
 * in the context:
 *
 *  1. ADOPTS: the factory uses Boot's `TransactionTemplate` verbatim (reference identity), so the
 *     user's / Boot's TX semantics — timeout, propagation, isolation — are preserved instead of
 *     being silently replaced by a fresh default `TransactionTemplate`.
 *
 *  2. VALIDATES: even though Boot's TT is adopted, `validatePtmDataSourceMatch` still runs and
 *     fails the context refresh when the PTM is bound to a different DataSource than okapi's
 *     outbox DataSource. The presence of Boot's TT does NOT bypass the safety net.
 */
class TransactionTemplateHijackProofTest : FunSpec({

    // Spring Boot 3.x: org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
    // Spring Boot 4.x: org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
    val txAutoConfigClass: Class<*> = listOf(
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
    ).firstNotNullOfOrNull { fqcn ->
        try {
            Class.forName(fqcn)
        } catch (_: ClassNotFoundException) {
            null
        }
    } ?: error(
        "TransactionAutoConfiguration not on test classpath. Without it neither test below can " +
            "exercise the Boot-auto-TT scenario. Check that spring-boot-transaction (4.x) or " +
            "spring-boot-autoconfigure (3.x) is on testRuntimeClasspath.",
    )

    test("ADOPTS: okapiTransactionRunner reuses Spring Boot's auto-configured TransactionTemplate verbatim") {
        val ds: DataSource = SimpleDriverDataSource()
        val ourPtm: PlatformTransactionManager = DataSourceTransactionManager(ds)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, txAutoConfigClass))
            .withBean(DataSource::class.java, { ds })
            .withBean("ourPtm", PlatformTransactionManager::class.java, { ourPtm })
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .run { ctx ->
                ctx.startupFailure.shouldBeNull()
                val bootTt = ctx.getBean(TransactionTemplate::class.java).shouldNotBeNull()
                val runner = ctx.getBean(TransactionRunner::class.java).shouldBeInstanceOf<SpringTransactionRunner>()
                withClue("okapiTransactionRunner must adopt Boot's TransactionTemplate by reference, not wrap a fresh default TT") {
                    runner.transactionTemplate.shouldBeSameInstanceAs(bootTt)
                }
            }
    }

    test("VALIDATES: Boot's auto-TT does NOT bypass validatePtmDataSourceMatch (wrong-DS PTM still fails refresh)") {
        val dsA: DataSource = SimpleDriverDataSource()
        val dsB: DataSource = SimpleDriverDataSource()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, txAutoConfigClass))
            .withBean(
                "dsB",
                DataSource::class.java,
                { dsB },
                org.springframework.beans.factory.config.BeanDefinitionCustomizer { it.isPrimary = true },
            )
            .withBean("dsA", DataSource::class.java, { dsA })
            .withBean("dstA", PlatformTransactionManager::class.java, { DataSourceTransactionManager(dsA) })
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .run { ctx ->
                ctx.startupFailure.shouldNotBeNull()
                ctx.startupFailure!!.stackTraceToString() shouldContain
                    "is bound to a different DataSource than okapi's outbox DataSource"
            }
    }
})
