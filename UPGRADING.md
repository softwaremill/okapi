# Upgrading

This guide contains actions required when upgrading between incompatible okapi versions.
For the full release history, see [CHANGELOG.md](CHANGELOG.md).

## From 1.x to 2.0.0

### MySQL: `DATETIME(6)` timestamp columns (#138)

Changeset `003__datetime_columns` converts `created_at`, `updated_at` and `last_attempt` from
`TIMESTAMP(6)` to `DATETIME(6)` holding UTC. With the bundled Liquibase changelog it runs
automatically at startup; otherwise apply
[`003__datetime_columns.sql`](okapi-mysql/src/main/resources/com/softwaremill/okapi/db/mysql/003__datetime_columns.sql)
yourself, **including its `SET time_zone` statements**, before starting 2.0.0 workers.

- **Stop every 1.x instance, publishers included, before 003 runs.** 1.x binds these columns as
  `java.sql.Timestamp`, which Connector/J shifts into the JVM (or `connectionTimeZone`) zone. Against
  `DATETIME` columns a 1.x instance on a non-UTC JVM writes local wall-clock time instead of UTC and
  misreads rows written by 2.0. A rolling deploy that mixes the two is only safe if every 1.x JVM
  runs in UTC.
- **Rows written by 1.x may read back shifted if the JVM and MySQL zones differed.** Without
  `connectionTimeZone`, 1.x sent `Timestamp`s as JVM-local wall-clock time and MySQL interpreted them
  in the session `time_zone`; when the two zones differed, the stored instant was off by the
  difference, and 1.x reading it back undid the shift. 003 converts what is actually stored, so 2.0
  reads such rows off by that difference: old `PENDING` rows sort out of order, the lag gauge is skewed
  and old `DELIVERED` rows are purged early or late. If your JVMs and MySQL session ran in different
  zones, drain the outbox (let every `PENDING` row be delivered) before upgrading.
- **Locking.** The `ALTER TABLE` copies `okapi_outbox` (`ALGORITHM=COPY, LOCK=SHARED`). Before it
  starts it waits for a metadata lock behind every open transaction on the table, such as a 1.x worker
  delivering a claimed batch, and while it waits every new statement on the table, reads included,
  queues behind it. The changeset bounds that wait with `lock_wait_timeout = 30` seconds; on timeout
  the migration fails and runs again on the next start. During the copy, writes, including
  `publish()`, wait. Stop the workers first, and purge delivered rows or pick a quiet period on a
  large table.
- Queries or dashboards that read these columns directly now get UTC wall-clock values, not values
  converted to the session time zone.

## From 0.2.x to 1.0.0

Releases up to 0.2.x used the domain table `outbox` and the shared Liquibase tracking
tables `databasechangelog` and `databasechangeloglock`. Later versions use
`okapi_outbox` and dedicated Liquibase tracking tables by default.

Stop all okapi processors and complete the following steps before starting 1.0.0.

### PostgreSQL

Rename the table and its indexes in place. The rows, including pending messages, are
preserved.

```sql
ALTER TABLE outbox RENAME TO okapi_outbox;
ALTER INDEX idx_outbox_status_last_attempt RENAME TO idx_okapi_outbox_status_last_attempt;
ALTER INDEX idx_outbox_status_created_at RENAME TO idx_okapi_outbox_status_created_at;
```

### MySQL

Rename the table in place, then remove the legacy indexes. The 1.0.0 changelog recreates
them under their new names.

```sql
RENAME TABLE outbox TO okapi_outbox;
ALTER TABLE okapi_outbox
    DROP INDEX idx_outbox_status_last_attempt,
    DROP INDEX idx_outbox_status_created_at;
```

### Liquibase tracking tables

By default, 1.0.0 creates `okapi_databasechangelog` and
`okapi_databasechangeloglock`. Let them start empty: the consolidated 1.0.0 changeset
will recognize the renamed table and record the current schema without deleting its
rows.

To keep using the existing shared tracking tables instead, configure their names before
the first startup:

```yaml
okapi:
  liquibase:
    changelog-table: databasechangelog
    changelog-lock-table: databasechangeloglock
```

Do not copy the 0.2.x Liquibase rows into the new tracking table. Version 1.0.0 uses a
consolidated changeset with a different file name and checksum.
