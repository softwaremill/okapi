package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.core.OutboxStore
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.context.support.GenericApplicationContext
import java.time.Instant

/**
 * Shared no-op test doubles + a bean-registration helper for okapi-spring-boot autoconfig slice
 * tests. Previously these were copy-pasted verbatim into 7+ test files; a single source means an
 * `OutboxStore` interface change is a one-line edit, not a 7-file sweep.
 */

internal fun stubStore() = object : OutboxStore {
    override fun persist(entry: OutboxEntry) = entry
    override fun claimPending(limit: Int) = emptyList<OutboxEntry>()
    override fun updateAfterProcessing(entry: OutboxEntry) = entry
    override fun removeDeliveredBefore(time: Instant, limit: Int) = 0
    override fun findOldestCreatedAt(statuses: Set<OutboxStatus>) = emptyMap<OutboxStatus, Instant>()
    override fun countByStatuses() = emptyMap<OutboxStatus, Long>()
}

internal fun stubDeliverer() = stubDelivererWithType("stub")

internal fun stubDelivererWithType(t: String) = object : MessageDeliverer {
    override val type = t
    override fun deliver(entry: OutboxEntry) = DeliveryResult.Success
}

/**
 * Registers a bean via [GenericApplicationContext.registerBeanDefinition], optionally `@Primary`.
 * Collapses the `BeanDefinitionBuilder.genericBeanDefinition(...).beanDefinition.apply { isPrimary }`
 * boilerplate that the multi-DataSource tests repeat for every DataSource / PTM bean.
 */
internal inline fun <reified T : Any> GenericApplicationContext.registerBean(
    name: String,
    primary: Boolean = false,
    crossinline supplier: () -> T,
) {
    registerBeanDefinition(
        name,
        BeanDefinitionBuilder.genericBeanDefinition(T::class.java) { supplier() }.beanDefinition
            .apply { isPrimary = primary },
    )
}
