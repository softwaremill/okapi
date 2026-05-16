package com.softwaremill.okapi.springboot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.ResourceTransactionManager

/**
 * Pins the Spring 7.0.7 `ObjectProvider` and `PlatformTransactionManager` semantics that
 * `OutboxAutoConfiguration.okapiTransactionRunner` relies on. If a future Spring upgrade
 * changes any of these behaviors, these tests fail loudly so the autoconfig logic gets
 * audited rather than silently breaking.
 *
 * Specifically the autoconfig assumes:
 * 1. `getIfUnique()` returns null (NOT throws) when 2+ PTM beans exist without `@Primary`.
 *    Used to distinguish "no PTM" from "multiple PTMs" via stream count.
 * 2. `getIfUnique()` returns the `@Primary` bean when present.
 * 3. `getIfAvailable()` THROWS `NoUniqueBeanDefinitionException` for 2+ non-primary
 *    candidates — the previous fix used this and surfaced a misleading error; switching to
 *    `getIfUnique()` was a deliberate semantic change.
 * 4. `DataSourceTransactionManager` IS-A `ResourceTransactionManager`, with
 *    `resourceFactory == DataSource`. Our PTM↔DS validation depends on this cast.
 * 5. PTMs that extend `AbstractPlatformTransactionManager` directly (e.g. Exposed
 *    `SpringTransactionManager`, `JpaTransactionManager`) do NOT implement
 *    `ResourceTransactionManager` — meaning we cannot extract their DataSource for validation
 *    and must fall back to a WARN log + require explicit `okapi.transaction-manager-qualifier`.
 */
class SpringObjectProviderSemanticsAssumptionsTest : FunSpec({

    test("ObjectProvider.getIfUnique() returns the @Primary PTM when multiple PTMs exist") {
        ApplicationContextRunner()
            .withUserConfiguration(TwoPtmsWithPrimaryConfig::class.java)
            .run { ctx ->
                ctx.getBeanProvider(PlatformTransactionManager::class.java).getIfUnique()
                    .shouldBeSameInstanceAs(ctx.getBean("primaryTm", PlatformTransactionManager::class.java))
            }
    }

    test("ObjectProvider.getIfUnique() returns null for 2+ non-primary PTMs (does NOT throw)") {
        ApplicationContextRunner()
            .withUserConfiguration(TwoPtmsNoPrimaryConfig::class.java)
            .run { ctx ->
                ctx.getBeanProvider(PlatformTransactionManager::class.java).getIfUnique().shouldBeNull()
            }
    }

    test(
        "ObjectProvider.getIfAvailable() THROWS NoUniqueBeanDefinitionException for 2+ non-primary PTMs (this is why we use getIfUnique)",
    ) {
        ApplicationContextRunner()
            .withUserConfiguration(TwoPtmsNoPrimaryConfig::class.java)
            .run { ctx ->
                val provider = ctx.getBeanProvider(PlatformTransactionManager::class.java)
                val thrown = runCatching { provider.getIfAvailable() }.exceptionOrNull()
                (thrown is NoUniqueBeanDefinitionException) shouldBe true
            }
    }

    test("DataSourceTransactionManager implements ResourceTransactionManager and exposes its DataSource via resourceFactory") {
        val ds = SimpleDriverDataSource()
        val dst: PlatformTransactionManager = DataSourceTransactionManager(ds)
        (dst is ResourceTransactionManager) shouldBe true
        (dst as ResourceTransactionManager).resourceFactory.shouldBeSameInstanceAs(ds)
    }

    test("AbstractPlatformTransactionManager subclasses (e.g. Exposed bridge / JPA-style) do NOT implement ResourceTransactionManager") {
        val tm: PlatformTransactionManager = DummyAbstractPtm()
        (tm is ResourceTransactionManager) shouldBe false
    }
})

@Configuration(proxyBeanMethods = false)
private class TwoPtmsNoPrimaryConfig {
    @Bean
    fun firstTm(): PlatformTransactionManager = DummyAbstractPtm()

    @Bean
    fun secondTm(): PlatformTransactionManager = DummyAbstractPtm()
}

@Configuration(proxyBeanMethods = false)
private class TwoPtmsWithPrimaryConfig {
    @Bean
    @Primary
    fun primaryTm(): PlatformTransactionManager = DummyAbstractPtm()

    @Bean
    fun secondaryTm(): PlatformTransactionManager = DummyAbstractPtm()
}

private class DummyAbstractPtm : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: org.springframework.transaction.TransactionDefinition) {}
    override fun doCommit(status: DefaultTransactionStatus) {}
    override fun doRollback(status: DefaultTransactionStatus) {}
}
