--liquibase formatted sql
--changeset outbox:003

-- TIMESTAMP WITHOUT TIME ZONE stored the writer JVM's local wall-clock time (pgjdbc renders a
-- java.sql.Timestamp in the JVM default zone), so ordering, lag and purge broke across mixed-zone
-- fleets and DST fall-back (issue #135). TIMESTAMPTZ stores instants.
--
-- Changing the type interprets each existing value in the session TimeZone. pgjdbc sets that to the
-- JVM default zone, so when the bundled Liquibase changelog runs inside the application it reads the
-- values in the same zone the application wrote them in. When applying this script by hand, first run
-- SET TIME ZONE '<the zone your okapi JVMs run in>'; in the same session.
--
-- The ALTER needs an ACCESS EXCLUSIVE lock. It first waits behind every open transaction on
-- okapi_outbox (e.g. a worker delivering a claimed batch), and while it waits every new statement on
-- the table queues behind it; SET LOCAL lock_timeout bounds that wait to 30 s for this transaction
-- only, after which the migration fails and runs again on the next start. Holding the lock, it
-- rewrites the table unless the session zone is UTC and rebuilds its indexes; reads and writes to
-- okapi_outbox, including publish(), wait until it finishes.
SET LOCAL lock_timeout = '30s';

ALTER TABLE okapi_outbox
    ALTER COLUMN created_at TYPE TIMESTAMPTZ,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ,
    ALTER COLUMN last_attempt TYPE TIMESTAMPTZ;
