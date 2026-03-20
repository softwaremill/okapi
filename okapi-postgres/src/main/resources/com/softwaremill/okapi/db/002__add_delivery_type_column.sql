--liquibase formatted sql
--changeset outbox:002

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS delivery_type VARCHAR(50);

UPDATE outbox SET delivery_type = delivery_metadata->>'type' WHERE delivery_type IS NULL;

ALTER TABLE outbox ALTER COLUMN delivery_type SET NOT NULL;
