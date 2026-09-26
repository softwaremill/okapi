CREATE TABLE okapi_outbox (
    id UUID NOT NULL PRIMARY KEY,
    message_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    delivery_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    retries INT NOT NULL DEFAULT 0,
    last_attempt TIMESTAMP,
    last_error TEXT,
    delivery_metadata JSONB NOT NULL
);
INSERT INTO okapi_outbox
SELECT md5(n::text)::uuid, 'event', '{}',
       CASE WHEN n <= 900000 THEN 'unhandled' ELSE 'handled_' || lpad(((n - 900001) / 1000)::text, 2, '0') END,
       'PENDING', timestamp '2024-01-01' + n * interval '1 microsecond',
       timestamp '2024-01-01', 0, NULL, NULL, '{}'::jsonb
FROM generate_series(1, 1000000) AS n;
CREATE INDEX idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
CREATE INDEX idx_okapi_outbox_status_delivery_created_id ON okapi_outbox (status, delivery_type, created_at, id);
ANALYZE okapi_outbox;
