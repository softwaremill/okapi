package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MysqlOutboxStoreTest : FunSpec({
    val db = MysqlTestSupport()

    outboxStoreContractTests(
        dbName = "mysql",
        storeFactory = { MysqlOutboxStore(Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
