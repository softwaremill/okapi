package com.softwaremill.okapi.core

import java.sql.Connection

/**
 * Provides a JDBC [Connection] from the current transactional context.
 *
 * Implementations bridge okapi's [OutboxStore] with the caller's transaction mechanism:
 * - `okapi-spring-boot`: uses `DataSourceUtils.getConnection()` — works with JPA, JDBC, jOOQ, MyBatis, Exposed
 * - `okapi-exposed`: uses Exposed's `TransactionManager.current().connection` — for Ktor/standalone Exposed
 * - Standalone: user-provided lambda wrapping a `DataSource` or `ThreadLocal<Connection>`
 *
 * The returned connection is **borrowed** from the current transaction — the caller must NOT close it.
 */
fun interface ConnectionProvider {
    fun getConnection(): Connection
}
