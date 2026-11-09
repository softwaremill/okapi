--liquibase formatted sql
--changeset outbox:004

-- next_attempt_at: when a PENDING entry is next due (issue #131). A retriable failure pushes it out
-- by the RetryPolicy backoff; the claim skips entries that are not yet due.
--
-- The DEFAULT keeps rows inserted by 1.x instances during a rolling deploy claimable (they do not
-- know the column). CURRENT_TIMESTAMP is not volatile, so PostgreSQL 11+ records it in the catalog
-- instead of rewriting the table. The ADD COLUMN needs an ACCESS EXCLUSIVE lock, held until the
-- transaction of this step commits, so the step does nothing else; lock_timeout bounds the wait.
SET LOCAL lock_timeout = '30s';

ALTER TABLE okapi_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

--changeset outbox:004-backfill

-- In its own transaction, so it takes only row locks: existing PENDING rows get their created_at and
-- keep their order instead of all becoming due at the migration time.
UPDATE okapi_outbox SET next_attempt_at = created_at WHERE status = 'PENDING' AND next_attempt_at <> created_at;

--changeset outbox:004-claim-index runInTransaction:false

-- The route-aware claim walks (delivery_type, next_attempt_at, id) up to now, so entries that are
-- backing off are never scanned. It replaces the (delivery_type, created_at, id) index from 002.
-- A failed or cancelled CREATE INDEX CONCURRENTLY leaves an INVALID index behind; drop it first so a
-- retried changeset rebuilds it.
DROP INDEX CONCURRENTLY IF EXISTS idx_okapi_outbox_pending_delivery_next_attempt_id;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_okapi_outbox_pending_delivery_next_attempt_id
    ON okapi_outbox (delivery_type, next_attempt_at, id)
    WHERE status = 'PENDING';

DROP INDEX CONCURRENTLY IF EXISTS idx_okapi_outbox_pending_delivery_created_id;
