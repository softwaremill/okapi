package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec

class PostgresTimestampColumnsTest : FunSpec({
    val db = PostgresTestSupport()

    timestampColumnTests(
        dbName = "postgres",
        jdbcProvider = { db.jdbc },
        storeFactory = { PostgresOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        utcText = { column -> "to_char($column AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')" },
        setSessionZone = "SET TIME ZONE 'Asia/Kolkata'",
    )
})
