package com.softwaremill.okapi.test.transaction

import com.softwaremill.okapi.core.DeliveryInfo
import com.softwaremill.okapi.core.DeliveryResult
import com.softwaremill.okapi.core.MessageDeliverer
import com.softwaremill.okapi.core.OutboxEntry
import com.softwaremill.okapi.core.OutboxEntryProcessor
import com.softwaremill.okapi.core.OutboxMessage
import com.softwaremill.okapi.core.OutboxProcessor
import com.softwaremill.okapi.core.OutboxStore
import com.softwaremill.okapi.core.RetryPolicy
import com.softwaremill.okapi.core.RouteAwareOutboxStore
import com.softwaremill.okapi.core.TransactionRunner
import com.softwaremill.okapi.exposed.ExposedConnectionProvider
import com.softwaremill.okapi.exposed.ExposedTransactionRunner
import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.springboot.OutboxAutoConfiguration
import com.softwaremill.okapi.springboot.SpringConnectionProvider
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.DefaultJpaDialect
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Regression test for issue #129: under MySQL's default REPEATABLE READ, the processor's claim
 * (`FOR UPDATE SKIP LOCKED` on `(status, delivery_type, created_at, id)`) gap-locks the end of the
 * claimed type's range when its backlog is smaller than the batch. Every `publish()` of that type
 * inserts exactly there, so publishers blocked until the claiming transaction -- held open for the
 * whole delivery -- committed.
 *
 * Each case runs a real processor tick through the runner under test with a deliverer that blocks
 * mid-delivery, so the claim transaction stays open exactly as it does during a slow HTTP/Kafka call.
 * Going through [OutboxProcessor.processNext] (not a store claim method directly) keeps the test on
 * whatever claim query the processor actually runs; [RecordingStore] asserts that this is the
 * route-aware `claimPending(deliveryTypes, limit)` from #120, never the legacy `claimPending(limit)`.
 * While delivery is blocked, it publishes from
 * another connection with a short `innodb_lock_wait_timeout`. The REPEATABLE READ case is the
 * control: it proves the setup really reproduces the lock, so the READ COMMITTED cases passing
 * means the fix works, not that the test is blind.
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

    /** Runs [publishWithShortLockWait] while a processor tick inside [runner] is blocked mid-delivery on [store]. */
    fun publishWhileClaimIsOpen(runner: TransactionRunner, store: OutboxStore) {
        seedPending(2) // fewer than the batch size: the claim scans to the end of the PENDING range
        val deliverer = BlockingDeliverer()
        val recordingStore = RecordingStore(store as RouteAwareOutboxStore)
        val processor = OutboxProcessor(recordingStore, OutboxEntryProcessor(deliverer, RetryPolicy(maxRetries = 0), clock))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val tick = executor.submit<Int> { runner.runInTransaction { processor.processNext(10) } }
            check(deliverer.inDelivery.await(30, TimeUnit.SECONDS)) { "processor tick did not reach delivery" }
            try {
                publishWithShortLockWait()
            } finally {
                deliverer.release.countDown()
                tick.get(30, TimeUnit.SECONDS) shouldBe 2
                recordingStore.claims shouldContainExactly listOf("claimPending([${ClaimTestDeliveryInfo.type}], 10)")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun assertNotBlocked(runner: TransactionRunner, store: OutboxStore) {
        val start = System.nanoTime()
        publishWhileClaimIsOpen(runner, store)
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

    fun springContext(ptm: PlatformTransactionManager) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration::class.java))
        .withPropertyValues("okapi.processor.enabled=false")
        .withBean(DataSource::class.java, { db.dataSource })
        .withBean(PlatformTransactionManager::class.java, { ptm })
        .withBean(OutboxStore::class.java, { MysqlOutboxStore(SpringConnectionProvider(db.dataSource)) })
        .withBean(MessageDeliverer::class.java, { NoopDeliverer })

    fun hibernateEmfBean() = LocalContainerEntityManagerFactoryBean().apply {
        dataSource = db.dataSource
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        setPackagesToScan()
        afterPropertiesSet()
    }

    test("the Spring autoconfigured TransactionRunner does not block a concurrent publish") {
        springContext(DataSourceTransactionManager(db.dataSource)).run { ctx ->
            assertNotBlocked(ctx.getBean(TransactionRunner::class.java), ctx.getBean(OutboxStore::class.java))
        }
    }

    test("the Spring autoconfigured TransactionRunner over a Hibernate JpaTransactionManager does not block a concurrent publish") {
        val emfBean = hibernateEmfBean()
        try {
            springContext(JpaTransactionManager(emfBean.`object`!!)).run { ctx ->
                val isolationInTick = ctx.getBean(TransactionRunner::class.java).runInTransaction {
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() ?: -1
                }
                isolationInTick shouldBe Connection.TRANSACTION_READ_COMMITTED // HibernateJpaDialect accepts it
                assertNotBlocked(ctx.getBean(TransactionRunner::class.java), ctx.getBean(OutboxStore::class.java))
            }
        } finally {
            emfBean.destroy()
        }
    }

    test("JpaTransactionManager without a vendor JpaDialect: isolation is not pinned, so ticks still run") {
        // A raw EntityManagerFactory (not Spring's EntityManagerFactoryInfo proxy) leaves the PTM on
        // DefaultJpaDialect, which throws InvalidIsolationLevelException for any non-DEFAULT level.
        val emfBean = hibernateEmfBean()
        try {
            val ptm = JpaTransactionManager(emfBean.nativeEntityManagerFactory)
            ptm.jpaDialect.javaClass shouldBe DefaultJpaDialect::class.java
            springContext(ptm).run { ctx ->
                ctx.startupFailure.shouldBeNull()
                // Non-null result: SpringTransactionRunner rejects a null block result.
                val isolationPinned = ctx.getBean(TransactionRunner::class.java).runInTransaction {
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() != null
                }
                isolationPinned shouldBe false
            }
        } finally {
            emfBean.destroy()
        }
    }
})

private const val LOCK_WAIT_TIMEOUT_SECONDS = 3

/** Stands in for a slow transport: the first delivery parks until [release], holding the claim open. */
/**
 * Delegates to the real store and records which claim method the processor calls, so the test fails
 * if the processor stops using the route-aware claim this regression test is meant to cover.
 */
private class RecordingStore(private val delegate: RouteAwareOutboxStore) : RouteAwareOutboxStore by delegate {
    val claims = mutableListOf<String>()

    override fun claimPending(limit: Int): List<OutboxEntry> {
        claims += "claimPending($limit)"
        return delegate.claimPending(limit)
    }

    override fun claimPending(deliveryTypes: Set<String>, limit: Int): List<OutboxEntry> {
        claims += "claimPending($deliveryTypes, $limit)"
        return delegate.claimPending(deliveryTypes, limit)
    }

    // Overridden so the single-type call is recorded too instead of going straight to the delegate.
    override fun claimPending(deliveryType: String, limit: Int): List<OutboxEntry> = claimPending(setOf(deliveryType), limit)
}

private class BlockingDeliverer : MessageDeliverer {
    val inDelivery = CountDownLatch(1)
    val release = CountDownLatch(1)
    override val type: String = ClaimTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult {
        inDelivery.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "delivery was never released" }
        return DeliveryResult.Success
    }
}

private object NoopDeliverer : MessageDeliverer {
    override val type: String = ClaimTestDeliveryInfo.type
    override fun deliver(entry: OutboxEntry): DeliveryResult = DeliveryResult.Success
}

private object ClaimTestDeliveryInfo : DeliveryInfo {
    override val type: String = "claim-test"
    override fun serialize(): String = """{"type":"claim-test"}"""
}
