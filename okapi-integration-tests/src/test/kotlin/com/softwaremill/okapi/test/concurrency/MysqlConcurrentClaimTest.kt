package com.softwaremill.okapi.test.concurrency

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec

class MysqlConcurrentClaimTest : FunSpec({
    val db = MysqlTestSupport()

    concurrentClaimTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
