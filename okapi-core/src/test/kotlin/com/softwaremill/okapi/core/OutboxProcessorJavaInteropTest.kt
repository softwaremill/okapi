package com.softwaremill.okapi.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import java.time.Clock
import java.time.Instant

/**
 * Runs the Java call sites in [JavaOutboxProcessorConstruction], which compile only when
 * [OutboxProcessor]'s constructor carries `@JvmOverloads`. The real guard is that Java source
 * compiling — okapi-core has no other Java interop test, which is why the missing annotation
 * regressed unnoticed. The stubs are inert: the constructor only stores its arguments.
 */
class OutboxProcessorJavaInteropTest :
    FunSpec({
        val store =
            object : OutboxStore {
                override fun persist(entry: OutboxEntry) = entry

                override fun claimPending(limit: Int) = emptyList<OutboxEntry>()

                override fun updateAfterProcessing(entry: OutboxEntry) = entry

                override fun removeDeliveredBefore(time: Instant, limit: Int) = 0

                override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()

                override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
            }
        val entryProcessor =
            OutboxEntryProcessor(
                deliverer =
                object : MessageDeliverer {
                    override val type = "stub"

                    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
                },
                retryPolicy = RetryPolicy(maxRetries = 3),
                clock = Clock.systemUTC(),
            )

        test("Java can construct OutboxProcessor with trailing defaults omitted (@JvmOverloads)") {
            JavaOutboxProcessorConstruction.withStoreAndProcessor(store, entryProcessor).shouldNotBeNull()
            JavaOutboxProcessorConstruction.withListener(store, entryProcessor, null).shouldNotBeNull()
        }
    })
