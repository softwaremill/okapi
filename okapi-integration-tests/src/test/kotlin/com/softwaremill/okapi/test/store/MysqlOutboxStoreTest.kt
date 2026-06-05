package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec

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
})
