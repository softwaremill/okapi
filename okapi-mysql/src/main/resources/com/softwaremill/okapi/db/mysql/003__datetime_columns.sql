--liquibase formatted sql
--changeset outbox:003

-- TIMESTAMP stops at 2038-01-19 03:14:07 UTC (issue #138). DATETIME(6) has no such limit and,
-- unlike TIMESTAMP, is not converted through the session time zone: okapi stores UTC wall-clock
-- values in it. MODIFY converts the existing TIMESTAMP values as the session time zone renders
-- them, so pin the session to UTC for the conversion and restore it afterwards (Liquibase may run
-- on a pooled connection that the application reuses). This rebuilds the table, and writes to it
-- (including publish()) wait until the rebuild finishes.
SET @okapi_previous_time_zone = @@session.time_zone;
SET time_zone = '+00:00';

ALTER TABLE okapi_outbox
    MODIFY created_at   DATETIME(6) NOT NULL,
    MODIFY updated_at   DATETIME(6) NOT NULL,
    MODIFY last_attempt DATETIME(6) NULL;

SET time_zone = @okapi_previous_time_zone;
