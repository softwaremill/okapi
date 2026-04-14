package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.ConnectionProvider
import org.springframework.jdbc.datasource.DataSourceUtils
import java.sql.Connection
import javax.sql.DataSource

/**
 * Spring-aware [ConnectionProvider] that retrieves the JDBC connection
 * bound to the current Spring-managed transaction.
 *
 * Uses [DataSourceUtils.getConnection] — the standard Spring mechanism
 * that works transparently with any [org.springframework.transaction.PlatformTransactionManager]:
 * JPA, JDBC, jOOQ, MyBatis, Exposed, etc.
 */
class SpringConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
    override fun getConnection(): Connection = DataSourceUtils.getConnection(dataSource)
}
