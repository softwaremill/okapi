\echo '=== index sizes ==='
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname = 'okapi_outbox' ORDER BY indexrelname;

SELECT pg_size_pretty(pg_relation_size('okapi_outbox')) AS table_size;

PREPARE claim(varchar, int) AS
    SELECT * FROM okapi_outbox WHERE status = $1 ORDER BY created_at ASC LIMIT $2 FOR UPDATE SKIP LOCKED;

-- Warm the cache so numbers reflect steady state, not first-touch I/O.
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;

\echo ''
\echo '=== A. literal predicate (what a psql-only check would show) ==='
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
SELECT * FROM okapi_outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 10 FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo ''
\echo '=== B. bound parameter, CUSTOM plan (value substituted at plan time) ==='
SET plan_cache_mode = force_custom_plan;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;

\echo ''
\echo '=== C. bound parameter, GENERIC plan (status = $1 unknown at plan time) ==='
SET plan_cache_mode = force_generic_plan;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;

\echo ''
\echo '=== D. bound parameter, plan_cache_mode = auto, 7th execution (what pgjdbc hits) ==='
SET plan_cache_mode = auto;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXECUTE claim('PENDING', 10);
ROLLBACK;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) EXECUTE claim('PENDING', 10);
ROLLBACK;
