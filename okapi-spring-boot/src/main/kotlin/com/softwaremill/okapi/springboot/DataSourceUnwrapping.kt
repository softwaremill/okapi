package com.softwaremill.okapi.springboot

import org.springframework.jdbc.datasource.DelegatingDataSource
import java.util.Collections
import java.util.IdentityHashMap
import javax.sql.DataSource

/**
 * Result of walking a [DelegatingDataSource] chain (e.g. a `TransactionAwareDataSourceProxy`) to
 * its concrete backing DataSource.
 *
 * Shared between [OutboxAutoConfiguration] (startup PTM<->DataSource binding check) and
 * [SpringTransactionContextValidator] (runtime `publish()` check) so both agree on what "the same
 * DataSource" means. Before this was shared (issue #91), the startup check unwrapped
 * `DelegatingDataSource` chains before comparing identity but the runtime check compared the raw
 * bean reference — a `TransactionAwareDataSourceProxy` (Spring's own documented pattern: proxy as
 * the DataSource bean, PlatformTransactionManager on the raw target) passed startup validation and
 * then made every `publish()` fail, since the PTM's bound resource lives under the raw target, not
 * the proxy.
 */
internal sealed interface Unwrapped {
    data class Resolved(val ds: DataSource) : Unwrapped

    /**
     * Unwrap stopped before reaching a concrete backing DataSource. Identity comparison would
     * be inconclusive — callers must NOT treat this as a mismatch.
     */
    data class Unresolvable(val stoppedAt: DataSource, val reason: Reason) : Unwrapped

    enum class Reason { CYCLE, NULL_TARGET }
}

// Iterative walk with an IdentityHashMap visited-set: guards against cyclic chains
// (Spring's setTargetDataSource has no cycle check). Identity, not equals(), because a
// custom DS overriding equals() to delegate to its target could trigger false early
// termination on a valid chain.
internal fun unwrapDataSource(ds: DataSource): Unwrapped {
    val seen: MutableSet<DataSource> = Collections.newSetFromMap(IdentityHashMap())
    var current: DataSource = ds
    while (current is DelegatingDataSource) {
        if (!seen.add(current)) return Unwrapped.Unresolvable(current, Unwrapped.Reason.CYCLE)
        val target = current.targetDataSource
            ?: return Unwrapped.Unresolvable(current, Unwrapped.Reason.NULL_TARGET)
        current = target
    }
    return Unwrapped.Resolved(current)
}

internal fun describeUnwrap(side: String, original: DataSource, unwrapped: Unwrapped): String = when (unwrapped) {
    is Unwrapped.Resolved -> "$side side: $original resolved to ${unwrapped.ds}"
    is Unwrapped.Unresolvable -> "$side side: $original stopped at ${unwrapped.stoppedAt} (${unwrapped.reason})"
}
