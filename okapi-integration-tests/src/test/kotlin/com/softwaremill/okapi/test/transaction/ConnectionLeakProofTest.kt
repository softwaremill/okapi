package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxId
import com.softwaremill.okapi.core.OutboxStatus
import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.test.support.CountingDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Clock

/**
 * Proves that [SpringConnectionProvider] releases every JDBC connection it borrows from
 * the pool when store methods are called outside a Spring transaction — the exact path
 * that previously leaked because the old `getConnection(): Connection` contract had no
 * release hook. Every physical `Connection.close()` is counted via a wrapping DataSource,
 * and the assertion `opened == closed` must hold at the end of each test.
 */
class ConnectionLeakProofTest : FunSpec({

    val container = PostgreSQLContainer<Nothing>("postgres:16")
    lateinit var counter: CountingDataSource
    lateinit var store: PostgresOutboxStore
    val clock: Clock = Clock.systemUTC()

    beforeSpec {
        container.start()
        val raw = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
        }
        runLiquibase(container)
        counter = CountingDataSource(raw)
        store = PostgresOutboxStore(SpringConnectionProvider(counter), clock)
    }

    afterSpec {
        container.stop()
    }

    beforeEach {
        counter.delegate.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE okapi_outbox") }
        }
        counter.opened.set(0)
        counter.closed.set(0)
    }

    test("read-only store methods release every borrowed connection") {
        val iterations = 25
        val methodsPerIteration = 3
        repeat(iterations) {
            store.countByStatuses()
            store.findOldestCreatedAt(setOf(OutboxStatus.PENDING, OutboxStatus.DELIVERED))
            store.claimPending(10)
        }

        counter.opened.get() shouldBe counter.closed.get()
        counter.opened.get() shouldBe iterations * methodsPerIteration
    }

    test("full lifecycle (persist, claim, update, purge) releases every borrowed connection") {
        val now = clock.instant()
        val entry = OutboxEntry(
            outboxId = OutboxId.new(),
            messageType = "test.event",
            payload = """{"k":"v"}""",
            deliveryType = "stub",
            status = OutboxStatus.PENDING,
            createdAt = now,
            updatedAt = now,
            retries = 0,
            lastAttempt = null,
            lastError = null,
            deliveryMetadata = """{"stub":true}""",
        )

        store.persist(entry)
        val claimed = store.claimPending(10)
        claimed.size shouldBe 1
        claimed.first().outboxId shouldBe entry.outboxId

        store.updateAfterProcessing(
            claimed.first().copy(status = OutboxStatus.DELIVERED, lastAttempt = clock.instant()),
        )
        store.countByStatuses()[OutboxStatus.DELIVERED] shouldBe 1L

        val removed = store.removeDeliveredBefore(clock.instant().plusSeconds(3600), 10)
        removed shouldBe 1
        store.countByStatuses()[OutboxStatus.DELIVERED] shouldBe 0L

        counter.opened.get() shouldBe counter.closed.get()
    }
})

private fun runLiquibase(container: PostgreSQLContainer<Nothing>) {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
        val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase("com/softwaremill/okapi/db/changelog.xml", ClassLoaderResourceAccessor(), db).use { it.update("") }
    }
}
