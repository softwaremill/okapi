--liquibase formatted sql
--changeset outbox:002

CREATE INDEX idx_okapi_outbox_status_last_attempt ON okapi_outbox(status, last_attempt);
