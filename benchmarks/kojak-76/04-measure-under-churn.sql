-- Simulate production churn: rows enter PENDING and leave as DELIVERED, leaving dead
-- index entries in the PENDING key range of both indexes. autovacuum will NOT fire here —
-- its threshold on a 1M-row table is 50 + 0.2*1M = ~200k changes — which is exactly the
-- realistic steady state this ticket is really about.
DO $$
DECLARE r int;
BEGIN
    FOR r IN 1..30 LOOP
        INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, delivery_metadata)
        SELECT gen_random_uuid(), 'Churn', '{}', 'kafka', 'PENDING', now(), now(), 0, '{}'::jsonb
        FROM generate_series(1, 1000);

        UPDATE okapi_outbox SET status = 'DELIVERED', last_attempt = now(), updated_at = now()
        WHERE message_type = 'Churn' AND status = 'PENDING';
    END LOOP;
END $$;

\echo '=== sizes after 30k PENDING->DELIVERED transitions, no vacuum ==='
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname = 'okapi_outbox' ORDER BY indexrelname;

PREPARE claim(varchar, int) AS
    SELECT * FROM okapi_outbox WHERE status = $1 ORDER BY created_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED;
\o /dev/null
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
\o

\echo ''
\echo '=== WITH partial index (bound param, default plan_cache_mode) ==='
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;

DROP INDEX idx_okapi_outbox_pending_created_at;
DEALLOCATE claim;
PREPARE claim2(varchar, int) AS
    SELECT * FROM okapi_outbox WHERE status = $1 ORDER BY created_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED;
\o /dev/null
BEGIN; EXECUTE claim2('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim2('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim2('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim2('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim2('PENDING', 10); ROLLBACK;
\o

\echo ''
\echo '=== WITHOUT partial index, same churned state (composite index only) ==='
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim2('PENDING', 10);
ROLLBACK;
