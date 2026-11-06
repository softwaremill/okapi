--liquibase formatted sql

-- next_attempt_at: when a PENDING entry is next due, in UTC like the other DATETIME columns (issue
-- #131). A retriable failure pushes it out by the RetryPolicy backoff; the claim skips entries that
-- are not yet due. The new claim index replaces the (status, delivery_type, created_at, id) index
-- from 002.
--
-- MySQL commits every DDL statement on its own, so a changeset holding several would leave the schema
-- half-changed if a later statement failed (e.g. on lock_wait_timeout) and could not run again. Each
-- step is therefore its own changeset, skipped by its precondition when it already took effect.

--changeset outbox:004
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND COLUMN_NAME = 'next_attempt_at'
-- Adding a nullable column at the end is an INSTANT change on MySQL 8.0.12+ (in place on older
-- versions). It is NULL until the backfill below; 1.x instances must be stopped before 003, so
-- nothing else inserts rows without it.
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;
ALTER TABLE okapi_outbox ADD COLUMN next_attempt_at DATETIME(6) NULL;
SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;

--changeset outbox:004-backfill
-- PENDING rows keep their order; FAILED rows get a value too, so one set back to PENDING by hand is
-- claimable. DELIVERED rows never need one and are read with next_attempt_at = created_at.
UPDATE okapi_outbox SET next_attempt_at = created_at WHERE next_attempt_at IS NULL AND status <> 'DELIVERED';

--changeset outbox:004-claim-index
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND INDEX_NAME = 'idx_okapi_outbox_status_delivery_next_attempt_id'
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;
ALTER TABLE okapi_outbox
    ADD INDEX idx_okapi_outbox_status_delivery_next_attempt_id (status, delivery_type, next_attempt_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;

--changeset outbox:004-drop-created-index
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'okapi_outbox' AND INDEX_NAME = 'idx_okapi_outbox_status_delivery_created_id'
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;
ALTER TABLE okapi_outbox
    DROP INDEX idx_okapi_outbox_status_delivery_created_id,
    ALGORITHM=INPLACE, LOCK=NONE;
SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;
