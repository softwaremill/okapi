package com.softwaremill.okapi.core

import java.sql.Connection

/**
 * Supplies a JDBC [Connection] to a user block, binding its lifetime to the block's scope.
 *
 * Implementations bridge okapi's [OutboxStore] with the caller's transaction mechanism
 * (Spring, Exposed, standalone). The connection passed to [block] is **borrowed** —
 * callers must never close it. Disposal is handled either by the provider itself
 * (e.g. Spring's `DataSourceUtils.releaseConnection` after the block returns or throws)
 * or by an outer scope that supplies the connection (e.g. an Exposed `transaction { }`
 * block, a test's thread-local binding).
 *
 * Some implementations require an active transaction on the calling thread
 * (e.g. `okapi-exposed` throws if `TransactionManager.current()` is unset);
 * others work both inside and outside of one (`okapi-spring-boot`).
 */
interface ConnectionProvider {
    fun <T> withConnection(block: (Connection) -> T): T
}
