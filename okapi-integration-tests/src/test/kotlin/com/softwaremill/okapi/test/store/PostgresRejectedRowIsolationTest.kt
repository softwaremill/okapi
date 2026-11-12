package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.postgres.PostgresOutboxStore
import com.softwaremill.okapi.test.support.PostgresTestSupport
import io.kotest.core.spec.style.FunSpec

class PostgresRejectedRowIsolationTest : FunSpec({
    val db = PostgresTestSupport()

    rejectedRowIsolationTests(
        dbName = "postgres",
        jdbcProvider = { db.jdbc },
        storeFactory = { PostgresOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        installRejectingTrigger = {
            db.jdbc.withTransaction {
                db.jdbc.withConnection { conn ->
                    conn.createStatement().use {
                        it.execute(
                            """
                            CREATE FUNCTION okapi_test_reject() RETURNS trigger AS ${'$'}${'$'}
                            BEGIN
                                IF NEW.last_error LIKE '%$REJECTED_ERROR_MARKER%' OR NEW.message_type = '$ALWAYS_REJECTED_TYPE' THEN
                                    RAISE EXCEPTION 'rejected by test trigger' USING ERRCODE = '22000';
                                END IF;
                                RETURN NEW;
                            END ${'$'}${'$'} LANGUAGE plpgsql
                            """.trimIndent(),
                        )
                        it.execute(
                            "CREATE TRIGGER okapi_test_reject BEFORE UPDATE ON okapi_outbox " +
                                "FOR EACH ROW EXECUTE FUNCTION okapi_test_reject()",
                        )
                    }
                }
            }
        },
    )
})
