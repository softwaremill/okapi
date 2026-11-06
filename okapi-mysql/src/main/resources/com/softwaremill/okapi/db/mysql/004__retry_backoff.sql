--liquibase formatted sql
--changeset outbox:004

-- next_attempt_at: when a PENDING entry is next due, in UTC like the other DATETIME columns (issue
-- #131). A retriable failure pushes it out by the RetryPolicy backoff; the claim skips entries that
-- are not yet due. It is NULL only on rows that were not PENDING when this ran: 1.x instances must be
-- stopped before 003, so nothing else inserts without it. Adding a nullable column at the end is an
-- INSTANT change on MySQL 8.0.12+ (in place on older versions).
--
-- The new claim index replaces the (status, delivery_type, created_at, id) index from 002.
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET SESSION lock_wait_timeout = 30;

ALTER TABLE okapi_outbox ADD COLUMN next_attempt_at DATETIME(6) NULL;

UPDATE okapi_outbox SET next_attempt_at = created_at WHERE status = 'PENDING';

ALTER TABLE okapi_outbox
    ADD INDEX idx_okapi_outbox_status_delivery_next_attempt_id (status, delivery_type, next_attempt_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE okapi_outbox
    DROP INDEX idx_okapi_outbox_status_delivery_created_id,
    ALGORITHM=INPLACE, LOCK=NONE;

SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;
