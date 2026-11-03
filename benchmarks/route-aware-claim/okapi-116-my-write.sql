USE okapi_bench;
START TRANSACTION;
SET @started = NOW(6);
INSERT INTO okapi_outbox
SELECT CONCAT('b', SUBSTRING(id, 2)), message_type, payload, delivery_type, status,
       created_at, updated_at, retries, last_attempt, last_error, delivery_metadata
FROM okapi_outbox
WHERE id <= LPAD(20000, 36, '0')
ORDER BY id LIMIT 20000;
SELECT TIMESTAMPDIFF(MICROSECOND, @started, NOW(6)) / 1000 AS insert_20k_ms;
ROLLBACK;
START TRANSACTION;
SET @started = NOW(6);
UPDATE okapi_outbox SET status = 'DELIVERED'
WHERE id <= LPAD(20000, 36, '0');
SELECT TIMESTAMPDIFF(MICROSECOND, @started, NOW(6)) / 1000 AS update_20k_ms;
ROLLBACK;
