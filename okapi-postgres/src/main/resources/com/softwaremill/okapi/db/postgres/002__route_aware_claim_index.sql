--liquibase formatted sql
--changeset outbox:002 runInTransaction:false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_okapi_outbox_status_delivery_created_id
    ON okapi_outbox (status, delivery_type, created_at, id);
