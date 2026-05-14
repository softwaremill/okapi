--liquibase formatted sql
--changeset outbox:002

ALTER TABLE okapi_outbox ADD COLUMN IF NOT EXISTS delivery_type VARCHAR(50);

UPDATE okapi_outbox SET delivery_type = delivery_metadata->>'type' WHERE delivery_type IS NULL;

ALTER TABLE okapi_outbox ALTER COLUMN delivery_type SET NOT NULL;
