--liquibase formatted sql
--changeset outbox:003

CREATE INDEX idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
