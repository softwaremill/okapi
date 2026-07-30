package com.softwaremill.okapi.springboot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [unwrapDataSource]: nested chains, null targets, and cycles.
 *
 * Extracted from [OutboxAutoConfiguration] (issue #91) into its own top-level utility, shared with
 * [SpringTransactionContextValidator], so the startup PTM<->DataSource check and the runtime
 * `publish()` check can no longer independently drift on what "the same DataSource" means.
 */
class DataSourceUnwrappingTest : FunSpec({
    test("returns Resolved with the input itself when not a DelegatingDataSource") {
        val raw: DataSource = SimpleDriverDataSource()
        val result = unwrapDataSource(raw)
        result.shouldBeInstanceOf<Unwrapped.Resolved>()
        result.ds shouldBeSameInstanceAs raw
    }

    test("unwraps a single-level TransactionAwareDataSourceProxy to Resolved(raw)") {
        val raw: DataSource = SimpleDriverDataSource()
        val proxy: DataSource = TransactionAwareDataSourceProxy(raw)
        val result = unwrapDataSource(proxy)
        result.shouldBeInstanceOf<Unwrapped.Resolved>()
        result.ds shouldBeSameInstanceAs raw
    }

    test("unwraps a nested chain TADP -> LCDP -> raw down to Resolved(raw)") {
        val raw: DataSource = SimpleDriverDataSource()
        val nested: DataSource = TransactionAwareDataSourceProxy(LazyConnectionDataSourceProxy(raw))
        val result = unwrapDataSource(nested)
        result.shouldBeInstanceOf<Unwrapped.Resolved>()
        result.ds shouldBeSameInstanceAs raw
    }

    test("returns Unresolvable(NULL_TARGET) when a DelegatingDataSource has no targetDataSource") {
        // LazyConnectionDataSourceProxy ships with a no-arg constructor that leaves targetDataSource null
        // until setTargetDataSource is called. The helper must surface this as an explicit Unresolvable
        // outcome so callers do not mistake a not-yet-wired proxy for a mismatched DataSource.
        val proxy: DataSource = LazyConnectionDataSourceProxy()
        val result = unwrapDataSource(proxy)
        result.shouldBeInstanceOf<Unwrapped.Unresolvable>()
        result.stoppedAt shouldBeSameInstanceAs proxy
        result.reason shouldBe Unwrapped.Reason.NULL_TARGET
    }

    test("returns Unresolvable(CYCLE) on a self-referencing DelegatingDataSource").config(timeout = 2.seconds) {
        val cyclic = LazyConnectionDataSourceProxy()
        cyclic.setTargetDataSource(cyclic)
        val result = unwrapDataSource(cyclic)
        result.shouldBeInstanceOf<Unwrapped.Unresolvable>()
        result.stoppedAt shouldBeSameInstanceAs cyclic
        result.reason shouldBe Unwrapped.Reason.CYCLE
    }

    test("returns Unresolvable(CYCLE) on a longer cycle (A -> B -> A)").config(timeout = 2.seconds) {
        val a = LazyConnectionDataSourceProxy()
        val b = LazyConnectionDataSourceProxy()
        a.setTargetDataSource(b)
        b.setTargetDataSource(a)
        val result = unwrapDataSource(a)
        result.shouldBeInstanceOf<Unwrapped.Unresolvable>()
        result.stoppedAt shouldBeSameInstanceAs a
        result.reason shouldBe Unwrapped.Reason.CYCLE
    }
})
