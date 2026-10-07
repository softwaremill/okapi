BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO okapi_outbox
SELECT md5(('bench' || n)::text)::uuid, 'event', '{}', 'handled_00', 'PENDING',
       timestamp '2024-02-01' + n * interval '1 microsecond',
       timestamp '2024-02-01', 0, NULL, NULL, '{}'::jsonb
FROM generate_series(1, 20000) AS n;
ROLLBACK;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE okapi_outbox SET status = 'DELIVERED'
WHERE id >= '00000000-0000-0000-0000-000000000000'::uuid
  AND id < '05000000-0000-0000-0000-000000000000'::uuid;
ROLLBACK;
