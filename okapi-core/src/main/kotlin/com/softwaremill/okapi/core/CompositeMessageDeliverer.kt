package com.softwaremill.okapi.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Routes delivery to the correct [MessageDeliverer] based on [OutboxEntry.deliveryType].
 *
 * Fails permanently for any entry whose type has no registered deliverer,
 * rather than throwing — so the outbox processor can move it to FAILED
 * and continue with remaining entries.
 *
 * [dispatch] selects how a batch spanning several transports is executed; see [TransportDispatch].
 */
class CompositeMessageDeliverer @JvmOverloads constructor(
    deliverers: List<MessageDeliverer>,
    private val dispatch: TransportDispatch = TransportDispatch.PARALLEL,
) : MessageDeliverer {
    override val type: String = "composite"

    private val registry: Map<String, MessageDeliverer> = buildMap {
        deliverers.forEach { deliverer ->
            val type = deliverer.type
            val previous = this[type]
            if (previous != null) {
                throw IllegalArgumentException(
                    "Duplicate MessageDeliverer type '$type': " +
                        "${previous.javaClass.name} and ${deliverer.javaClass.name}",
                )
            }
            put(type, deliverer)
        }
    }

    override fun deliver(entry: OutboxEntry): DeliveryResult {
        val messageDeliverer = registry[entry.deliveryType]
            ?: return DeliveryResult.PermanentFailure("No deliverer registered for type '${entry.deliveryType}'")
        return messageDeliverer.deliver(entry)
    }

    /**
     * Groups entries by [OutboxEntry.deliveryType] and delegates each sub-batch
     * to the matching deliverer's [MessageDeliverer.deliverBatch]. Results are
     * re-assembled in original input order.
     *
     * Transport groups are I/O-independent, so under [TransportDispatch.PARALLEL] (the default)
     * they are dispatched **concurrently**: one virtual thread per group, with the last group run
     * on the calling thread rather than handed to a thread that the caller would only wait for. A
     * heterogeneous batch therefore costs ~`max(Tᵢ)` instead of `T₁ + T₂ + ... + Tₙ`. A batch with
     * a single delivery type — the common case — takes a fast path that starts no thread and
     * allocates no executor, whatever the configured [TransportDispatch].
     *
     * Because a group may then run on a thread other than the caller's, [MessageDeliverer]
     * implementations must be thread-safe and must not depend on caller thread-locals (SLF4J MDC,
     * Spring's `TransactionSynchronizationManager`, security context); deliverers that cannot
     * satisfy that opt out via [TransportDispatch.SEQUENTIAL].
     *
     * The outbox transaction is open across delivery, and stays that way: the scheduler wraps the
     * whole [OutboxProcessor.processNext] cycle — claim, deliver, update — in one transaction, so
     * that `FOR UPDATE SKIP LOCKED` holds its row locks until the results are written. What this
     * dispatch changes is which thread the transport runs on, not that scope. The store round-trips
     * still run on the calling thread inside the transaction, but a group handed to a virtual
     * thread does **not** inherit the caller's transaction-bound resources: a deliverer that
     * implicitly joins the outbox transaction (Spring's `DataSourceUtils`, Exposed's thread-bound
     * transaction) gets a fresh connection instead — precisely what [TransportDispatch.SEQUENTIAL]
     * is for. For a heterogeneous batch, parallel dispatch also shortens how long that transaction
     * stays open, from `sum(Tᵢ)` to ~`max(Tᵢ)`.
     *
     * Upholds [MessageDeliverer.deliverBatch]'s "must not throw" contract even when a transport
     * does not: a deliverer that throws, or that returns no result for some of its entries, fails
     * only its own entries — as [DeliveryResult.RetriableFailure], so nothing is silently dropped —
     * while the other transports' results stand. Entries whose type has no registered deliverer are
     * mapped to [DeliveryResult.PermanentFailure], consistent with [deliver].
     */
    override fun deliverBatch(entries: List<OutboxEntry>): List<DeliveryOutcome> {
        if (entries.isEmpty()) return emptyList()

        val groups = entries.groupBy { it.deliveryType }.map { (type, group) -> TransportGroup(type, group) }
        val outcomes = when {
            groups.size == 1 -> deliverGroup(groups.single())
            dispatch == TransportDispatch.SEQUENTIAL -> groups.flatMap { group -> deliverGroup(group) }
            else -> deliverGroupsInParallel(groups)
        }
        val resultByEntry: Map<OutboxEntry, DeliveryResult> = outcomes.associate { it.entry to it.result }

        return entries.map { entry ->
            val result = resultByEntry[entry]
                ?: DeliveryResult.RetriableFailure(
                    "Deliverer for type '${entry.deliveryType}' returned no result for entry ${entry.outboxId}",
                )
            DeliveryOutcome(entry, result)
        }
    }

    /**
     * Fires every group but the last on its own virtual thread, then runs the last one on the
     * calling thread before awaiting the rest. Returned outcomes are unordered — [deliverBatch]
     * re-assembles input order from its `Map<OutboxEntry, DeliveryResult>` lookup.
     */
    private fun deliverGroupsInParallel(groups: List<TransportGroup>): List<DeliveryOutcome> {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            val inFlight = groups.dropLast(1).map { group -> group to executor.submit(Callable { deliverGroup(group) }) }
            val onCallerThread = deliverGroup(groups.last())
            inFlight.flatMap { (group, future) -> await(group, future) } + onCallerThread
        } finally {
            // shutdownNow() without awaiting termination, deliberately not `use`/close(): close()
            // loops on awaitTermination(1, DAYS) until every task ends, so one transport that
            // ignores interruption would pin the caller here — and with it the scheduler's
            // shutdown — long after we have results for the whole batch.
            //
            // On the normal path this is a no-op: every group was awaited above, so nothing is
            // running. It only bites on the interrupt path, and there it is a deliberate trade:
            // interruption is the only lever available (an HTTP request already on the wire or a
            // `producer.send()` in flight cannot be cancelled), so a deliverer that ignores it may
            // complete its send after this method has reported that group as retriable — the entry
            // is then delivered again on a later claim. That is the at-least-once contract okapi
            // already documents, and the same window exists in HttpMessageDeliverer.deliverBatch,
            // which likewise abandons in-flight `sendAsync` futures when the caller is interrupted.
            // Waiting would narrow it — the real result could then be persisted instead of a
            // retriable one — but only by reintroducing the unbounded block this `finally` exists
            // to avoid. Duplicate delivery is the documented contract; an indefinite hang is not.
            // The tasks hold no shared state, and virtual threads are daemon threads, so an
            // abandoned one cannot hold up JVM exit.
            executor.shutdownNow()
        }
    }

    /**
     * Awaits via [Future.get] rather than [java.util.concurrent.CompletableFuture.join] so an
     * interrupt on the caller (scheduler shutdown) is observed instead of ignored. The flag is
     * restored on the interrupted thread itself, so the remaining `get()` calls fail fast and the
     * `finally` in [deliverGroupsInParallel] interrupts the still-running groups — without waiting
     * for them, so a transport that ignores interruption cannot hold up the caller.
     *
     * Reporting an interrupted group as [DeliveryResult.RetriableFailure] while its send may still
     * be in flight is what makes okapi at-least-once rather than exactly-once: that entry can be
     * delivered twice. See the trade-off spelled out in [deliverGroupsInParallel], and the
     * "Duplicate delivery is possible" guidance in the README — consumers must be idempotent.
     */
    private fun await(group: TransportGroup, future: Future<List<DeliveryOutcome>>): List<DeliveryOutcome> = try {
        future.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        group.failAll(DeliveryResult.RetriableFailure("Interrupted while delivering type '${group.type}': ${describe(e)}"))
    } catch (e: Exception) {
        // deliverGroup() already converts every Exception into outcomes, so this only sees an
        // ExecutionException wrapping an Error, or a CancellationException from shutdownNow().
        // Retriable rather than permanent: a broken transport must not dead-letter messages that
        // were never actually attempted.
        group.failAll(DeliveryResult.RetriableFailure("Delivery of type '${group.type}' failed: ${describe(e.cause ?: e)}"))
    }

    private fun deliverGroup(group: TransportGroup): List<DeliveryOutcome> {
        val deliverer = registry[group.type]
            ?: return group.failAll(DeliveryResult.PermanentFailure("No deliverer registered for type '${group.type}'"))

        return try {
            deliverer.deliverBatch(group.entries)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            group.failAll(DeliveryResult.RetriableFailure("Interrupted while delivering type '${group.type}': ${describe(e)}"))
        } catch (e: Exception) {
            group.failAll(DeliveryResult.RetriableFailure("Deliverer for type '${group.type}' threw: ${describe(e)}"))
        }
    }

    private fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"

    private data class TransportGroup(val type: String, val entries: List<OutboxEntry>) {
        fun failAll(result: DeliveryResult): List<DeliveryOutcome> = entries.map { DeliveryOutcome(it, result) }
    }
}
