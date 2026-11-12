package com.softwaremill.okapi.test.store

import com.softwaremill.okapi.mysql.MysqlOutboxStore
import com.softwaremill.okapi.test.support.MysqlTestSupport
import io.kotest.core.spec.style.FunSpec
import java.sql.DriverManager

class MysqlRejectedRowIsolationTest : FunSpec({
    val db = MysqlTestSupport()

    rejectedRowIsolationTests(
        dbName = "mysql",
        jdbcProvider = { db.jdbc },
        storeFactory = { MysqlOutboxStore(db.jdbc) },
        startDb = { db.start() },
        stopDb = { db.stop() },
        truncate = { db.truncate() },
        installRejectingTrigger = {
            // With binary logging on, creating a trigger takes SUPER, which only root has.
            DriverManager.getConnection(db.container.jdbcUrl, "root", db.container.password).use { conn ->
                conn.createStatement().use {
                    it.execute(
                        """
                        CREATE TRIGGER okapi_test_reject BEFORE UPDATE ON okapi_outbox FOR EACH ROW
                        BEGIN
                            IF NEW.last_error LIKE '%$REJECTED_ERROR_MARKER%' OR NEW.message_type = '$ALWAYS_REJECTED_TYPE' THEN
                                SIGNAL SQLSTATE '22000' SET MESSAGE_TEXT = 'rejected by test trigger';
                            END IF;
                        END
                        """.trimIndent(),
                    )
                }
            }
        },
    )
})
