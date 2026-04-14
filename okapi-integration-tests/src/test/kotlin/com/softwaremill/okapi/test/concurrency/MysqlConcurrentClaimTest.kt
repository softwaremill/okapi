package com.softwaremill.okapi.test.concurrency

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MysqlConcurrentClaimTest : FunSpec({
    val db = MysqlTestSupport()

    concurrentClaimTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc, Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
