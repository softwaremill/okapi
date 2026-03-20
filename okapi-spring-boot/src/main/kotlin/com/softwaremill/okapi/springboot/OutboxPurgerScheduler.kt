package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.OutboxPurger
import com.softwaremill.okapi.core.OutboxStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import java.time.Clock
import java.time.Duration

/**
 * Spring lifecycle wrapper for [OutboxPurger].
 *
 * Starts purging after all beans are initialized, stops on context close.
 * Enabled by default; disable with `okapi.purger.enabled=false`.
 */
class OutboxPurgerScheduler(
    outboxStore: OutboxStore,
    retentionDays: Long = 7,
    intervalMinutes: Long = 60,
    clock: Clock = Clock.systemUTC(),
) : SmartInitializingSingleton,
    DisposableBean {

    private val purger = OutboxPurger(
        outboxStore = outboxStore,
        retentionDuration = Duration.ofDays(retentionDays),
        intervalMs = intervalMinutes * 60 * 1_000,
        clock = clock,
    )

    override fun afterSingletonsInstantiated() {
        purger.start()
    }

    override fun destroy() {
        purger.stop()
    }
}
