package com.softwaremill.okapi.test.concurrency

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec

class PostgresConcurrentClaimTest : FunSpec({
    val db = PostgresTestSupport()

    concurrentClaimTests(
        dbName = "postgres",
        jdbcProvider = { db.jdbc },
        storeFactory = { PostgresOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
