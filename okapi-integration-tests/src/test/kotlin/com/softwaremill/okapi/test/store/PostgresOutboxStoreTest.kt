package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PostgresOutboxStoreTest : FunSpec({
    val db = PostgresTestSupport()

    outboxStoreContractTests(
        dbName = "postgres",
        storeFactory = { PostgresOutboxStore(Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
