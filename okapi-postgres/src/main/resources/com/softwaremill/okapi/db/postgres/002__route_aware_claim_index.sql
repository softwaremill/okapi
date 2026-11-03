--liquibase formatted sql
--changeset outbox:002 runInTransaction:false

-- A failed or cancelled CREATE INDEX CONCURRENTLY leaves an INVALID index behind. Drop it first so a
-- retried changeset rebuilds the index instead of IF NOT EXISTS silently keeping the invalid one.
DROP INDEX CONCURRENTLY IF EXISTS idx_okapi_outbox_pending_delivery_created_id;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_okapi_outbox_pending_delivery_created_id
    ON okapi_outbox (delivery_type, created_at, id)
    WHERE status = 'PENDING';
