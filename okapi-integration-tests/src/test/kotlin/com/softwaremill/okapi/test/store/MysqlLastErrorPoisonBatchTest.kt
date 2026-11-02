package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec

class MysqlLastErrorPoisonBatchTest : FunSpec({
    val db = MysqlTestSupport()

    lastErrorPoisonBatchTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        // `last_error` is TEXT: 65,535 bytes, and strict mode rejects anything longer.
        rejectedError = "HTTP 503: " + "x".repeat(100_000),
    )
})
