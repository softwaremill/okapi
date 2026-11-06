package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.time.Instant

class MysqlOutboxStoreTest : FunSpec({
    val db = MysqlTestSupport()

    outboxStoreContractTests(
        dbName = "mysql",
        storeFactory = {
            MysqlOutboxStore(
                connectionProvider = db.jdbc,
            )
        },
        jdbcProvider = { db.jdbc },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )

    test("[mysql] route-aware claim raises no server warnings") {
        val store = MysqlOutboxStore(connectionProvider = db.jdbc)
        val warnings = db.jdbc.withTransaction {
            store.claimPending(setOf("kafka", "http"), 10, Instant.now())
            db.jdbc.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SHOW WARNINGS").use { rs ->
                        generateSequence { if (rs.next()) "${rs.getInt("Code")}: ${rs.getString("Message")}" else null }.toList()
                    }
                }
            }
        }

        warnings.shouldBeEmpty()
    }
})
