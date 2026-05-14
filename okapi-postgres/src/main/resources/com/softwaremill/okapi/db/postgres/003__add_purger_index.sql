--liquibase formatted sql
--changeset outbox:003

CREATE INDEX idx_okapi_outbox_status_last_attempt ON okapi_outbox(status, last_attempt);
