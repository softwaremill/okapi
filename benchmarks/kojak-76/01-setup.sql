\timing off
-- Schema copied verbatim from 001__create_okapi_outbox_table.sql
CREATE TABLE IF NOT EXISTS okapi_outbox
(
    id                UUID         NOT NULL PRIMARY KEY,
    message_type      VARCHAR(255) NOT NULL,
    payload           TEXT         NOT NULL,
    delivery_type     VARCHAR(50)  NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retries           INT          NOT NULL DEFAULT 0,
    last_attempt      TIMESTAMP,
    last_error        TEXT,
    delivery_metadata JSONB        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_okapi_outbox_status_last_attempt ON okapi_outbox (status, last_attempt);
CREATE INDEX IF NOT EXISTS idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);

-- 1,000,000 DELIVERED (historical) rows, spread over the past ~90 days.
INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, delivery_metadata)
SELECT gen_random_uuid(),
       'OrderPlaced',
       '{"orderId":' || g || ',"amount":123.45,"currency":"EUR"}',
       CASE WHEN g % 3 = 0 THEN 'http' ELSE 'kafka' END,
       'DELIVERED',
       now() - (g || ' seconds')::interval,
       now() - (g || ' seconds')::interval,
       0,
       now() - (g || ' seconds')::interval,
       '{"topic":"orders"}'::jsonb
FROM generate_series(1, 1000000) g;

-- 1,000 PENDING rows: the live working set, newest rows in the table.
INSERT INTO okapi_outbox (id, message_type, payload, delivery_type, status, created_at, updated_at, retries, last_attempt, delivery_metadata)
SELECT gen_random_uuid(),
       'OrderPlaced',
       '{"orderId":' || g || ',"amount":123.45,"currency":"EUR"}',
       'kafka',
       'PENDING',
       now() - (g || ' milliseconds')::interval,
       now() - (g || ' milliseconds')::interval,
       0,
       NULL,
       '{"topic":"orders"}'::jsonb
FROM generate_series(1, 1000) g;

VACUUM ANALYZE okapi_outbox;
