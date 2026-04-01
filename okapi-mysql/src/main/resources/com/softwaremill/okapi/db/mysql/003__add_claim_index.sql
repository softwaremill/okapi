--liquibase formatted sql
--changeset outbox:003

CREATE INDEX idx_outbox_status_created_at ON outbox (status, created_at);
