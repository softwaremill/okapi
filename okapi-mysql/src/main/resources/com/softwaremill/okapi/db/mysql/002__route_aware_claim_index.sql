--liquibase formatted sql
--changeset outbox:002

ALTER TABLE okapi_outbox
    ADD INDEX idx_okapi_outbox_status_delivery_created_id (status, delivery_type, created_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
