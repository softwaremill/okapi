package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.TransactionContextValidator
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * Spring implementation of [TransactionContextValidator].
 *
 * Validates that the current thread is inside an active, non-read-only
 * Spring-managed transaction **on the specific [dataSource]** where
 * the outbox table lives.
 *
 * Uses [TransactionSynchronizationManager.getResource] to verify that
 * the outbox DataSource has an active connection bound in the current
 * transaction. This correctly handles multi-datasource setups:
 * a transaction on DataSource A will not satisfy validation when the
 * outbox lives on DataSource B.
 *
 * Note: [TransactionSynchronizationManager.isCurrentTransactionReadOnly] is a thread-global
 * flag, not scoped to a specific DataSource. In rare scenarios with concurrent transactions
 * on multiple DataSources with mixed read-only semantics (without REQUIRES_NEW suspension),
 * this flag may reflect the wrong transaction's read-only state. The [getResource][TransactionSynchronizationManager.getResource]
 * check is the primary DataSource-specific guard.
 */
internal class SpringTransactionContextValidator(
    dataSource: DataSource,
) : TransactionContextValidator {

    // Spring's own guidance is that a TransactionAwareDataSourceProxy (or any DelegatingDataSource
    // wrapper) must NOT be handed to a PlatformTransactionManager -- the PTM is always constructed
    // with the raw target, so it binds its transactional resource under that raw reference, never
    // under the wrapper. If [dataSource] is such a wrapper, resolving it here to the concrete
    // backing DataSource makes getResource() below find what the PTM actually bound, matching what
    // OutboxAutoConfiguration's startup PTM<->DataSource check already verifies (issue #91: the two
    // checks previously disagreed -- startup unwrapped the chain, this runtime check compared the
    // raw bean reference -- so a proxy that passed startup validation then made every publish()
    // fail). Resolved once here at construction time, not per-publish(): identity never changes
    // after wiring, and this runs on every publish() call.
    private val resolvedDataSource: DataSource = when (val unwrapped = unwrapDataSource(dataSource)) {
        is Unwrapped.Resolved -> unwrapped.ds
        is Unwrapped.Unresolvable -> error(
            "Could not resolve the outbox DataSource for transaction validation: " +
                describeUnwrap("outbox", dataSource, unwrapped) +
                ". Fix the proxy wiring (cycle in setTargetDataSource, or initialise the lazy " +
                "proxy's targetDataSource before context refresh).",
        )
    }

    override fun isInActiveReadWriteTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive() &&
        !TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
        TransactionSynchronizationManager.getResource(resolvedDataSource) != null

    override val failureMessage: String
        get() = "No active read-write transaction on the outbox DataSource. " +
            "Ensure publish() is called within a @Transactional method that uses the same DataSource as the outbox table. " +
            "In multi-datasource setups, verify okapi.datasource-qualifier is set correctly."
}
