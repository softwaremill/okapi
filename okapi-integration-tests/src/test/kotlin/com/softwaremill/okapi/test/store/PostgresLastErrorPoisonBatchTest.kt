package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec

class PostgresLastErrorPoisonBatchTest : FunSpec({
    val db = PostgresTestSupport()

    lastErrorPoisonBatchTests(
        dbName = "postgres",
        jdbcProvider = { db.jdbc },
        storeFactory = { PostgresOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        // Postgres text cannot hold NUL; pgjdbc refuses the parameter outright.
        rejectedError = "HTTP 500: binary\u0000body",
    )
})
