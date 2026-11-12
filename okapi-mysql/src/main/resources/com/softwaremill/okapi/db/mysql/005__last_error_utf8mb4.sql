--liquibase formatted sql

-- last_error holds transport and exception messages, which can contain any Unicode character, such as
-- an emoji in an HTTP error body (issue #141). 001 created the table without a character set, so it
-- took the schema's default. On a latin1 or utf8mb3 schema such a value fails in strict mode with
-- "Incorrect string value". The store then writes a placeholder instead, so the batch survives, but the
-- message is lost. utf8mb4 stores every character.
--
-- Changing a column's character set rebuilds the table, and writes, publish() included, wait until it
-- finishes. The precondition skips the rebuild where last_error is already utf8mb4: the MySQL 8 default,
-- so on most installations this does nothing.

--changeset outbox:005
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND COLUMN_NAME = 'last_error' AND CHARACTER_SET_NAME = 'utf8mb4'
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;
ALTER TABLE okapi_outbox MODIFY last_error TEXT CHARACTER SET utf8mb4 NULL;
SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;
