package com.softwaremill.okapi.exposed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.sql.Connection

class ExposedTransactionRunnerTest : FunSpec({

    val db = Database.connect(
        "jdbc:h2:mem:runner_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
    )

    fun isolationSeenBy(runner: ExposedTransactionRunner): Int = runner.runInTransaction {
        (TransactionManager.current().connection.connection as Connection).transactionIsolation
    }

    // The MySQL default (READ COMMITTED, #129) is covered against a real MySQL in
    // okapi-integration-tests' MysqlClaimDoesNotBlockPublishersTest.
    test("keeps the database's default isolation on a non-MySQL database") {
        // H2's own default is already READ COMMITTED, so configure another one to tell them apart.
        val serializableDb = Database.connect(
            "jdbc:h2:mem:runner_test_serializable_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE },
        )
        isolationSeenBy(ExposedTransactionRunner(serializableDb)) shouldBe Connection.TRANSACTION_SERIALIZABLE
    }

    test("honours an explicit READ COMMITTED") {
        isolationSeenBy(ExposedTransactionRunner(db, Connection.TRANSACTION_READ_COMMITTED)) shouldBe
            Connection.TRANSACTION_READ_COMMITTED
    }

    test("honours an explicit isolation level") {
        isolationSeenBy(ExposedTransactionRunner(db, Connection.TRANSACTION_SERIALIZABLE)) shouldBe
            Connection.TRANSACTION_SERIALIZABLE
    }

    test("null falls back to the database's default isolation") {
        isolationSeenBy(ExposedTransactionRunner(db, null)) shouldBe db.transactionManager.defaultIsolationLevel
    }
})
