\timing on
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_okapi_outbox_pending_created_at
    ON okapi_outbox (created_at)
    WHERE status = 'PENDING';
\timing off

ANALYZE okapi_outbox;

\echo '=== index sizes after ==='
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname = 'okapi_outbox' ORDER BY indexrelname;

PREPARE claim(varchar, int) AS
    SELECT * FROM okapi_outbox WHERE status = $1 ORDER BY created_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED;

\o /dev/null
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
\o

\echo ''
\echo '=== A. literal predicate ==='
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
SELECT * FROM okapi_outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 10 FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo ''
\echo '=== C. bound parameter, GENERIC plan ==='
SET plan_cache_mode = force_generic_plan;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;

\echo ''
\echo '=== D. bound parameter, auto, after 6 executions ==='
SET plan_cache_mode = auto;
\o /dev/null
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
BEGIN; EXECUTE claim('PENDING', 10); ROLLBACK;
\o
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;
