# Upgrading

This guide contains actions required when upgrading between incompatible okapi versions.
For the full release history, see [CHANGELOG.md](CHANGELOG.md).

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
