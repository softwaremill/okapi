package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec

class PostgresOutboxStoreTest : FunSpec({
    val db = PostgresTestSupport()

    outboxStoreContractTests(
        dbName = "postgres",
        storeFactory = {
            PostgresOutboxStore(
                connectionProvider = db.jdbc,
            )
        },
        jdbcProvider = { db.jdbc },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
    )
})
