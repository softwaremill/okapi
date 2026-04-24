package com.softwaremill.okapi.springboot

import com.softwaremill.okapi.core.ConnectionProvider
import org.springframework.jdbc.datasource.DataSourceUtils
import java.sql.Connection
import javax.sql.DataSource

/**
 * Spring-aware [ConnectionProvider] backed by [DataSourceUtils].
 *
 * [DataSourceUtils.releaseConnection] is a no-op when the connection is bound to an active
 * Spring transaction (Spring owns its lifecycle), and returns the connection to the pool
 * otherwise. Without the release, each call outside a Spring transaction would leak a
 * pooled connection.
 */
class SpringConnectionProvider(private val dataSource: DataSource) : ConnectionProvider {
    override fun <T> withConnection(block: (Connection) -> T): T {
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}
