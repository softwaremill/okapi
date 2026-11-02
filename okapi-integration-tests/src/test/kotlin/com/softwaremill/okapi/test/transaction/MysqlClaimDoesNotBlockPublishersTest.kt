package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.exposed.ExposedConnectionProvider
import com.softwaremill.okapi.exposed.ExposedTransactionRunner
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Regression test for issue #129: under MySQL's default REPEATABLE READ, the processor's claim
 * (`FOR UPDATE SKIP LOCKED` on `(status, created_at)`) gap-locks the end of the index when the
 * backlog is smaller than the batch. Every `publish()` inserts exactly there, so publishers blocked
 * until the claiming transaction -- held open for the whole delivery -- committed.
 *
 * Each case opens a claim through the runner under test, keeps that transaction open (standing in
 * for an in-flight delivery), and publishes from another connection with a short
 * `innodb_lock_wait_timeout`. The REPEATABLE READ case is the control: it proves the setup really
 * reproduces the lock, so the READ COMMITTED cases passing means the fix works, not that the test
 * is blind.
 */
class MysqlClaimDoesNotBlockPublishersTest : FunSpec({

    val db = MysqlTestSupport()
    val clock = Clock.systemUTC()

    beforeSpec { db.start() }
    afterSpec { db.stop() }
    beforeEach { db.truncate() }

    fun seedPending(count: Int) {
        val store = MysqlOutboxStore(db.jdbc)
        db.jdbc.withTransaction {
            repeat(count) {
                store.persist(OutboxEntry.createPending(OutboxMessage("seed", "{}"), ClaimTestDeliveryInfo, clock.instant()))
            }
        }
    }

    /** Publishes on its own connection, failing fast with a lock-wait timeout if the insert is blocked. */
    fun publishWithShortLockWait() {
        val store = MysqlOutboxStore(db.jdbc)
        db.jdbc.withTransaction {
            db.jdbc.withConnection { conn ->
                conn.createStatement().use { it.execute("SET SESSION innodb_lock_wait_timeout = $LOCK_WAIT_TIMEOUT_SECONDS") }
            }
            store.persist(OutboxEntry.createPending(OutboxMessage("published", "{}"), ClaimTestDeliveryInfo, clock.instant()))
        }
    }

    /** Runs [publishWithShortLockWait] while [runner] holds a claim transaction open on [claimStore]. */
    fun publishWhileClaimIsOpen(runner: TransactionRunner, claimStore: OutboxStore) {
        seedPending(2) // fewer than the batch size: the claim scans to the end of the PENDING range
        val claimed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val claim = executor.submit {
                runner.runInTransaction {
                    claimStore.claimPending(10) shouldHaveSize 2
                    claimed.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            check(claimed.await(30, TimeUnit.SECONDS)) { "claim transaction did not start" }
            try {
                publishWithShortLockWait()
            } finally {
                release.countDown()
                claim.get(30, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun assertNotBlocked(runner: TransactionRunner, claimStore: OutboxStore) {
        val start = System.nanoTime()
        publishWhileClaimIsOpen(runner, claimStore)
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) shouldBeLessThan LOCK_WAIT_TIMEOUT_SECONDS * 1000L
    }

    test("control: a REPEATABLE READ claim blocks a concurrent publish (reproduces #129)") {
        val database = Database.connect(db.dataSource)
        val runner = ExposedTransactionRunner(database, Connection.TRANSACTION_REPEATABLE_READ)
        val error = shouldThrow<SQLException> {
            publishWhileClaimIsOpen(runner, MysqlOutboxStore(ExposedConnectionProvider(database)))
        }
        error.message shouldContain "Lock wait timeout"
    }

    test("ExposedTransactionRunner's default isolation does not block a concurrent publish") {
        val database = Database.connect(db.dataSource)
        assertNotBlocked(ExposedTransactionRunner(database), MysqlOutboxStore(ExposedConnectionProvider(database)))
    }

    test("the Spring autoconfigured TransactionRunner does not block a concurrent publish") {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
            .withPropertyValues("okapi.processor.enabled=false")
            .withBean(DataSource::class.java, { db.dataSource })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(db.dataSource) })
            .withBean(OutboxStore::class.java, { MysqlOutboxStore(SpringConnectionProvider(db.dataSource)) })
            .withBean(MessageDeliverer::class.java, { NoopDeliverer })
            .run { ctx ->
                assertNotBlocked(ctx.getBean(TransactionRunner::class.java), ctx.getBean(OutboxStore::class.java))
            }
    }
})

private const val LOCK_WAIT_TIMEOUT_SECONDS = 3

private object NoopDeliverer : MessageDeliverer {
    override val type: String = ClaimTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult = DeliveryResult.Success
}

private object ClaimTestDeliveryInfo : DeliveryInfo {
    override val type: String = "claim-test"
    override fun serialize(): String = """{"type":"claim-test"}"""
}
