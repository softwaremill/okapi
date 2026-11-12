--liquibase formatted sql

-- last_error holds transport and exception messages, which can contain any Unicode character, such as
-- an emoji in an HTTP error body (issue #141). 001 created the table without a character set, so it
-- took the schema's default. On a latin1 or utf8mb3 schema such a value fails in strict mode with
-- "Incorrect string value". The store then writes a placeholder instead, so the batch survives, but the
-- message is lost. utf8mb4 stores every character.
--
-- The ALTER copies the table. It first waits for a metadata lock behind every open transaction that
-- touched okapi_outbox, such as a worker delivering a claimed batch, while every new statement on the
-- table, reads included, queues behind it. lock_wait_timeout bounds that wait to 30 s; on timeout this
-- migration fails and runs again on the next start. Writes, publish() included, then wait until the copy
-- finishes. The precondition skips all this where last_error is already utf8mb4, as on a schema created
-- by MySQL 8 with its default character set. Schemas created by MySQL 5.7, or by MariaDB builds that
-- default to latin1, are converted.

--changeset outbox:005
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND COLUMN_NAME = 'last_error' AND CHARACTER_SET_NAME = 'utf8mb4'
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;
ALTER TABLE okapi_outbox MODIFY last_error TEXT CHARACTER SET utf8mb4 NULL,
    ALGORITHM=COPY, LOCK=SHARED;
SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;
