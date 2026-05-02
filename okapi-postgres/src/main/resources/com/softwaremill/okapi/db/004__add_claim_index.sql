--liquibase formatted sql
--changeset outbox:004

CREATE INDEX IF NOT EXISTS idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
