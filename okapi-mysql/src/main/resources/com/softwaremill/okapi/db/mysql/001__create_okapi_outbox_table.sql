--liquibase formatted sql
--changeset outbox:001

CREATE TABLE IF NOT EXISTS okapi_outbox
(
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    message_type      VARCHAR(255) NOT NULL,
    payload           TEXT         NOT NULL,
    delivery_type     VARCHAR(50)  NOT NULL,
    status            VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    retries           INT          NOT NULL DEFAULT 0,
    last_attempt      TIMESTAMP(6) NULL,
    last_error        TEXT,
    delivery_metadata JSON         NOT NULL
);

CREATE INDEX idx_okapi_outbox_status_last_attempt ON okapi_outbox (status, last_attempt);

CREATE INDEX idx_okapi_outbox_status_created_at ON okapi_outbox (status, created_at);
