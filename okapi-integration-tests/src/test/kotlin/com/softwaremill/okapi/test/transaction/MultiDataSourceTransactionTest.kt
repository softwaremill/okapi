package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxPublisher
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.springboot.SpringOutboxPublisher
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldNotBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Clock
import javax.sql.DataSource

/**
 * Integration test verifying that [SpringOutboxPublisher] correctly validates
 * the DataSource-aware transactional context in a multi-datasource setup.
 *
 * Uses two separate PostgreSQL containers:
 * - **outboxContainer**: hosts the outbox table (Liquibase migration applied)
 * - **otherContainer**: a second database with no outbox table
 *
 * Both DataSources use plain [DataSourceTransactionManager].
 */
class MultiDataSourceTransactionTest : FunSpec({

    val outboxContainer = PostgreSQLContainer<Nothing>("postgres:16")
    val otherContainer = PostgreSQLContainer<Nothing>("postgres:16")

    lateinit var outboxDataSource: DataSource
    lateinit var otherDataSource: DataSource
    lateinit var outboxTxTemplate: TransactionTemplate
    lateinit var otherTxTemplate: TransactionTemplate
    lateinit var publisher: SpringOutboxPublisher
    lateinit var store: PostgresOutboxStore

    val clock: Clock = Clock.systemUTC()

    val stubDeliveryInfo = object : DeliveryInfo {
        override val type = "stub"
        override fun serialize(): String = """{"type":"stub"}"""
    }

    val testMessage = OutboxMessage(messageType = "test.event", payload = """{"key":"value"}""")

    beforeSpec {
        outboxContainer.start()
        otherContainer.start()

        outboxDataSource = PGSimpleDataSource().apply {
            setURL(outboxContainer.jdbcUrl)
            user = outboxContainer.username
            password = outboxContainer.password
        }

        otherDataSource = PGSimpleDataSource().apply {
            setURL(otherContainer.jdbcUrl)
            user = otherContainer.username
            password = otherContainer.password
        }

        // Run Liquibase migration only on the outbox database
        runLiquibase(outboxContainer)

        val outboxTxManager = DataSourceTransactionManager(outboxDataSource)
        outboxTxTemplate = TransactionTemplate(outboxTxManager)

        // Plain DataSourceTransactionManager for the other DataSource
        val otherTxManager = DataSourceTransactionManager(otherDataSource)
        otherTxTemplate = TransactionTemplate(otherTxManager)

        store = PostgresOutboxStore(SpringConnectionProvider(outboxDataSource), clock)
        val corePublisher = OutboxPublisher(store, clock)
        publisher = SpringOutboxPublisher(delegate = corePublisher, dataSource = outboxDataSource)
    }

    afterSpec {
        outboxContainer.stop()
        otherContainer.stop()
    }

    beforeEach {
        outboxDataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE outbox") }
        }
    }

    test("publish succeeds when in transaction on outbox DataSource") {
        val outboxId = outboxTxTemplate.execute {
            publisher.publish(testMessage, stubDeliveryInfo)
        }

        outboxId shouldNotBe null

        val counts = outboxTxTemplate.execute { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
    }

    test("publish fails with IllegalStateException when in transaction on OTHER DataSource") {
        shouldThrow<IllegalStateException> {
            otherTxTemplate.execute {
                publisher.publish(testMessage, stubDeliveryInfo)
            }
        }
    }

    test("publish fails with IllegalStateException when called outside any transaction") {
        shouldThrow<IllegalStateException> {
            publisher.publish(testMessage, stubDeliveryInfo)
        }
    }

    test("publish succeeds with nested transaction (savepoint) on outbox DataSource") {
        val outboxId = outboxTxTemplate.execute {
            // Nested call creates a savepoint (PROPAGATION_REQUIRED by default nests via savepoints)
            outboxTxTemplate.execute {
                publisher.publish(testMessage, stubDeliveryInfo)
            }
        }

        outboxId shouldNotBe null

        val counts = outboxTxTemplate.execute { store.countByStatuses() }
        counts shouldContain (OutboxStatus.PENDING to 1L)
    }
})

private fun runLiquibase(container: PostgreSQLContainer<Nothing>) {
    val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
    connection.close()
}
