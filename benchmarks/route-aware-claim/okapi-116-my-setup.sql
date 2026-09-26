CREATE DATABASE IF NOT EXISTS okapi_bench;
USE okapi_bench;
CREATE TABLE IF NOT EXISTS okapi_outbox (
    id CHAR(36) NOT NULL PRIMARY KEY,
    message_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    delivery_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    retries INT NOT NULL DEFAULT 0,
    last_attempt TIMESTAMP(6) NULL,
    last_error TEXT,
    delivery_metadata JSON NOT NULL
);
CREATE TABLE digit (n INT NOT NULL);
INSERT INTO digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
INSERT INTO okapi_outbox
SELECT LPAD(x.n, 36, '0'), 'event', '{}',
       CASE WHEN x.n <= 900000 THEN 'unhandled' ELSE CONCAT('handled_', LPAD(FLOOR((x.n - 900001) / 1000), 2, '0')) END,
       'PENDING', TIMESTAMPADD(MICROSECOND, x.n, '2024-01-01 00:00:00'),
       '2024-01-01 00:00:00', 0, NULL, NULL, JSON_OBJECT()
FROM (
  SELECT a.n + 10*b.n + 100*c.n + 1000*d.n + 10000*e.n + 100000*f.n + 1 AS n
  FROM digit a CROSS JOIN digit b CROSS JOIN digit c CROSS JOIN digit d CROSS JOIN digit e CROSS JOIN digit f
) x;
CREATE INDEX idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
CREATE INDEX idx_okapi_outbox_status_delivery_created_id ON okapi_outbox (status, delivery_type, created_at, id);
ANALYZE TABLE okapi_outbox;
DROP TABLE digit;
