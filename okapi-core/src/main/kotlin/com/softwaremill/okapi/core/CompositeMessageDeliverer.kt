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

    private val registry: Map<String, MessageDeliverer> = deliverers.associateBy { it.type }

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
     * satisfy that opt out via [TransportDispatch.SEQUENTIAL]. The outbox transaction itself is
     * unaffected either way: it wraps the claim/update round-trips in [OutboxProcessor], not the
     * transport I/O.
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
    private fun deliverGroupsInParallel(groups: List<TransportGroup>): List<DeliveryOutcome> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val inFlight = groups.dropLast(1).map { group -> group to executor.submit(Callable { deliverGroup(group) }) }
            val onCallerThread = deliverGroup(groups.last())
            inFlight.flatMap { (group, future) -> await(group, future) } + onCallerThread
        }

    /**
     * Awaits via [Future.get] rather than [java.util.concurrent.CompletableFuture.join] so an
     * interrupt on the caller (scheduler shutdown) is observed instead of ignored. The flag is
     * restored on the interrupted thread itself, which makes the remaining `get()` calls fail fast
     * and makes the enclosing `use` escalate to `shutdownNow()` for the still-running groups.
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
