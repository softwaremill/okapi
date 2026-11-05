--liquibase formatted sql
--changeset outbox:003

-- TIMESTAMP stops at 2038-01-19 03:14:07 UTC (issue #138). DATETIME(6) has no such limit and,
-- unlike TIMESTAMP, is not converted through the session time zone: okapi stores UTC wall-clock
-- values in it. MODIFY converts the existing TIMESTAMP values as the session time zone renders
-- them, so pin the session to UTC for the conversion. Liquibase may run on a pooled connection the
-- application reuses, so the session settings are restored afterwards -- on success only: if the
-- ALTER fails, Liquibase aborts startup and the connection keeps the values set here.
--
-- The ALTER copies the table. It first waits for a metadata lock behind every open transaction that
-- touched okapi_outbox (e.g. a 1.x worker delivering a claimed batch), and while it waits, every new
-- statement on the table queues behind it. lock_wait_timeout bounds that wait to 30 s instead of
-- MySQL's default of a year; on timeout the migration fails and runs again on the next start. Once
-- the copy starts, writes to okapi_outbox (including publish()) wait until it finishes.
SET @okapi_previous_time_zone = @@session.time_zone;
SET @okapi_previous_lock_wait_timeout = @@session.lock_wait_timeout;
SET time_zone = '+00:00';
SET SESSION lock_wait_timeout = 30;

ALTER TABLE okapi_outbox
    MODIFY created_at   DATETIME(6) NOT NULL,
    MODIFY updated_at   DATETIME(6) NOT NULL,
    MODIFY last_attempt DATETIME(6) NULL,
    ALGORITHM=COPY, LOCK=SHARED;

SET SESSION lock_wait_timeout = @okapi_previous_lock_wait_timeout;
SET time_zone = @okapi_previous_time_zone;
