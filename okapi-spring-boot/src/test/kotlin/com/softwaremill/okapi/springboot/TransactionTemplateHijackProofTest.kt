package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

/**
 * Reliable proof tests against the ultrareview claim:
 *   "Spring Boot's auto-configured TransactionTemplate hijacks okapiTransactionRunner — the factory
 *    short-circuits to the Boot-supplied template and skips PTM↔DataSource validation entirely."
 *
 * The previous single-test version asserted only that startup failed in the mismatch scenario, then
 * inferred "validation ran". That's brittle — context could fail for unrelated reasons, or autoconfig
 * ordering in slice tests could differ from production. These three tests pin down three independent
 * invariants so the conclusion is empirically forced:
 *
 *   1. PRECONDITION: Spring Boot's TransactionAutoConfiguration actually creates a `TransactionTemplate`
 *      bean in slice tests. If false, the hijack scenario cannot occur in this test harness and the
 *      other two tests prove nothing — the suite fails loudly instead of silently passing.
 *
 *   2. INTROSPECTION: in a single-DS happy path, `okapiTransactionRunner` produces a
 *      `SpringTransactionRunner` whose `TransactionTemplate.transactionManager` is the user's PTM.
 *      Combined with #1 this proves: even when Boot's TT bean exists, the factory does NOT pick it.
 *      (If the factory short-circuited via Boot's TT, the embedded PTM identity would not match.)
 *
 *   3. MISMATCH FAIL-FAST: in a 2-DS scenario with PTM bound to the wrong DS, startup fails with
 *      a literal substring from `validatePtmDataSourceMatch`'s `error(...)` message that no other
 *      Spring component emits — passing this assertion proves our validation code path was reached,
 *      not just that something failed.
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
        "TransactionAutoConfiguration not on test classpath. Without it the entire hijack scenario " +
            "cannot be reproduced — failing the suite loudly rather than silently passing. Check that " +
            "spring-boot-transaction (4.x) or spring-boot-autoconfigure (3.x) is on testRuntimeClasspath.",
    )

    test("PRECONDITION: Spring Boot's TransactionAutoConfiguration registers a TransactionTemplate bean") {
        val ds: DataSource = SimpleDriverDataSource()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(txAutoConfigClass))
            .withBean(DataSource::class.java, { ds })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(ds) })
            .run { ctx ->
                withClue(
                    "If this fails, Spring Boot's TransactionTemplateConfiguration was not triggered in slice " +
                        "tests — the hijack tests below would silently pass without testing anything. Investigate " +
                        "before trusting test #2 / #3 results.",
                ) {
                    ctx.getBean(TransactionTemplate::class.java).shouldNotBeNull()
                }
            }
    }

    test("INTROSPECTION: with Boot's TT in context, okapiTransactionRunner builds a TT around OUR PTM") {
        val ds: DataSource = SimpleDriverDataSource()
        val ourPtm: PlatformTransactionManager = DataSourceTransactionManager(ds)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java, txAutoConfigClass))
            .withBean(DataSource::class.java, { ds })
            .withBean("ourPtm", PlatformTransactionManager::class.java, { ourPtm })
            .withBean(OutboxStore::class.java, { stubStore() })
            .withBean(MessageDeliverer::class.java, { stubDeliverer() })
            .run { ctx ->
                ctx.startupFailure shouldBe null
                val runner = ctx.getBean(TransactionRunner::class.java)
                runner.shouldBeInstanceOf<SpringTransactionRunner>()
                // The TT that okapiTransactionRunner wraps must be bound to OUR PTM — not Boot's
                // auto-configured TT (which is the hijack failure mode). Reference identity, not
                // equality, since each PTM is a distinct object.
                withClue("okapiTransactionRunner is wrapping a TT whose internal PTM is NOT our ourPtm — hijack confirmed") {
                    runner.transactionTemplate.transactionManager shouldBe ourPtm
                }
            }
    }

    test("MISMATCH FAIL-FAST: PTM bound to wrong DataSource triggers validatePtmDataSourceMatch error") {
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
                val failure = ctx.startupFailure
                failure shouldNotBe null
                failure!!.stackTraceToString() shouldContain
                    "is bound to a different DataSource than okapi's outbox DataSource"
            }
    }
})

private fun stubStore() = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

private fun stubDeliverer() = object : MessageDeliverer {
    override val type = "stub"
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}
