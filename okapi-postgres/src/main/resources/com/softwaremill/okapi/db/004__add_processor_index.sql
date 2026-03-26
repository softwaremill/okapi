--liquibase formatted sql
--changeset outbox:004

CREATE INDEX IF NOT EXISTS idx_outbox_status_created_at ON outbox (status, created_at);
