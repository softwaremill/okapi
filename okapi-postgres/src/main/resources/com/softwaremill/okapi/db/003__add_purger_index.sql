--liquibase formatted sql
--changeset outbox:003

CREATE INDEX idx_outbox_status_last_attempt ON outbox(status, last_attempt);
