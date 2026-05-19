--liquibase formatted sql
--changeset outbox:001

CREATE TABLE IF NOT EXISTS okapi_outbox
(
    id                UUID         NOT NULL PRIMARY KEY,
    message_type      VARCHAR(255) NOT NULL,
    payload           TEXT         NOT NULL,
    delivery_type     VARCHAR(50)  NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retries           INT          NOT NULL DEFAULT 0,
    last_attempt      TIMESTAMP,
    last_error        TEXT,
    delivery_metadata JSONB        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_okapi_outbox_status_last_attempt ON okapi_outbox (status, last_attempt);

CREATE INDEX IF NOT EXISTS idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
