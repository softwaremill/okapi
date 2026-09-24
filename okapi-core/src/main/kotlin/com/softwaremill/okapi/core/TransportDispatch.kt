package com.softwaremill.okapi.core

/**
 * How [CompositeMessageDeliverer] dispatches a batch that spans more than one transport.
 *
 * A batch with a single delivery type — the common case — is unaffected by this setting: it always
 * runs inline on the calling thread, with no executor and no thread hop.
 */
enum class TransportDispatch {
    /**
     * Transport groups run concurrently, one virtual thread per group (the last group runs on the
     * calling thread), so a heterogeneous batch costs ~`max(Tᵢ)` instead of `T₁ + T₂ + ... + Tₙ`.
     *
     * The default. Requires [MessageDeliverer] implementations to tolerate running on a thread
     * other than the caller's — see [SEQUENTIAL] for when that does not hold.
     */
    PARALLEL,

    /**
     * Transport groups run one after another, entirely on the calling thread — the behaviour from
     * before [PARALLEL] became the default.
     *
     * An escape hatch for custom deliverers that depend on caller thread context: SLF4J MDC,
     * Spring's `TransactionSynchronizationManager` (e.g. a deliverer reading through
     * `DataSourceUtils` expecting to join the outbox transaction), or a thread-bound security
     * context. Costs `sum(Tᵢ)` for a heterogeneous batch.
     */
    SEQUENTIAL,
}
