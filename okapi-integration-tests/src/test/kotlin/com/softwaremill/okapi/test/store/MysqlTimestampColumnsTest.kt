package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec

class MysqlTimestampColumnsTest : FunSpec({
    val db = MysqlTestSupport()

    timestampColumnTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        utcText = { column -> "DATE_FORMAT($column, '%Y-%m-%d %H:%i:%s.%f')" },
        setSessionZone = "SET time_zone = '+05:00'",
    )
})
