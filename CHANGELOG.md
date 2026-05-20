# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Until `1.0.0`, breaking changes may appear in any release and are flagged with **BREAKING** below.

## [Unreleased]

### Changed (BREAKING)

- **Outbox domain table renamed `outbox` → `okapi_outbox`.** Indexes follow the rename
  (`idx_outbox_*` → `idx_okapi_outbox_*`). Host applications with a pre-existing `outbox`
  table are no longer affected — okapi creates its own table under the `okapi_` prefix.
  The new name is fixed; it is not configurable. ([#37](https://github.com/softwaremill/okapi/issues/37))
- **Liquibase tracking tables default to `okapi_databasechangelog` /
  `okapi_databasechangeloglock`.** Previously okapi shared the application's
  default `databasechangelog` / `databasechangeloglock`. Override the new defaults
  via configuration to keep the shared-table layout (see Added below).
  ([#37](https://github.com/softwaremill/okapi/issues/37))
- **okapi's Liquibase migrations consolidated into a single
  `001__create_okapi_outbox_table.sql` per database.** New installations create the
  full schema (table + indexes) from one changeset instead of replaying the change
  history; the resulting schema is unchanged. Existing installations from an earlier
  release: the `outbox:001` changeset checksum changed — they must start on a fresh
  okapi schema, or clear okapi's rows from `okapi_databasechangelog`, before upgrading.
- **`OutboxProcessorScheduler` / `OutboxPurgerScheduler` constructors now require a
  non-null `TransactionRunner`** (previously a nullable `TransactionTemplate?`, with
  `OutboxPurgerScheduler`'s parameter defaulted to `null`). Spring Boot autoconfig users
  are unaffected — the autoconfig derives a `TransactionRunner` from any
  `PlatformTransactionManager` on the classpath. Users constructing the schedulers
  directly must supply a `TransactionRunner` (e.g. `SpringTransactionRunner(template)` or
  a thin lambda wrapping their framework's native transaction primitive). The previous
  null-default silently degraded `FOR UPDATE SKIP LOCKED` to JDBC auto-commit, permitting
  duplicate delivery across processor instances. ([#49](https://github.com/softwaremill/okapi/pull/49))
- **`okapi-spring-boot` autoconfig refuses to start when it cannot verify the
  PlatformTransactionManager↔outbox-DataSource binding** in a multi-DataSource context.
  Specifically: if `extractDataSource` cannot determine the PTM's DataSource (e.g. JTA,
  Exposed's `SpringTransactionManager`, or any PTM that exposes neither a `DataSource`
  resourceFactory nor a public `getDataSource()`), AND the context has ≥2 `DataSource`
  beans, AND `okapi.transaction-manager-qualifier` is not set, the context refresh now
  fails with an actionable message. `okapi.datasource-qualifier` alone is not
  sufficient — it picks the outbox DataSource but does not constrain which PTM
  brackets it. Single-DataSource contexts and setups that explicitly name the PTM
  via `okapi.transaction-manager-qualifier` are unaffected. Escape hatch: supply an
  explicit `@Bean TransactionRunner` to bypass validation.
  ([#49](https://github.com/softwaremill/okapi/pull/49))

### Added

- `okapi.liquibase.changelog-table` — Spring Boot property that configures the
  `databaseChangeLogTable` of okapi's autoconfigured `SpringLiquibase` beans
  (`okapiPostgresLiquibase` / `okapiMysqlLiquibase`). Default: `okapi_databasechangelog`.
- `okapi.liquibase.changelog-lock-table` — likewise for `databaseChangeLogLockTable`.
  Default: `okapi_databasechangeloglock`.

### Migration from 0.2.x

These are breaking changes; existing deployments must take action before the first
`0.3.0` startup. The README has the full SQL: see
[Database migrations § Upgrading from 0.2.x](README.md#upgrading-from-02x).
Two paths are documented: rename in place (recommended) or stay on the legacy
changelog table names by overriding `okapi.liquibase.changelog-table` /
`changelog-lock-table`. The domain-table rename has no opt-out — run the
provided `ALTER TABLE ... RENAME TO okapi_outbox` script.

## [0.2.0] — 2026-04-29

### Added

- Observability: `OutboxProcessorListener` API and the `okapi-micrometer` module
  (counters, timers, gauges; Spring Boot Actuator integration). ([#27](https://github.com/softwaremill/okapi/pull/27))
- Multi-datasource transaction validation in `okapi-spring-boot`
  (`SpringTransactionContextValidator`, `okapi.datasource-qualifier` property). ([#17](https://github.com/softwaremill/okapi/pull/17))
- `@JvmOverloads` / `@JvmStatic` annotations across the public API for Java interop. ([#24](https://github.com/softwaremill/okapi/pull/24))
- Maven Central release pipeline. ([#18](https://github.com/softwaremill/okapi/pull/18))

### Changed

- `OutboxStore` migrated from JetBrains Exposed to plain JDBC in
  `okapi-postgres` and `okapi-mysql`. The Exposed-based path remains
  available via the optional `okapi-exposed` module. ([#26](https://github.com/softwaremill/okapi/pull/26))
- Configuration unification: `Duration` types throughout, dedicated
  `OutboxPurgerConfig` and `OutboxSchedulerConfig`. ([#16](https://github.com/softwaremill/okapi/pull/16))
- `OutboxProcessorScheduler` and `OutboxPurger` v2 — configurable interval,
  batch size, retention; reliable shutdown via `SmartLifecycle`. ([#11](https://github.com/softwaremill/okapi/pull/11), [#14](https://github.com/softwaremill/okapi/pull/14))

### Fixed

- Actionable error message in `ExposedConnectionProvider` when no transaction is
  bound to the current thread. ([#32](https://github.com/softwaremill/okapi/pull/32))
- `okapi-micrometer` artifact published to Maven Central; the
  `okapi.metrics.refresh-interval` property documented. ([#29](https://github.com/softwaremill/okapi/pull/29))

## [0.1.0] — 2026-04-07

Initial public release.

### Added

- Transactional outbox pattern for Kotlin/JVM with PostgreSQL and MySQL stores.
- `okapi-http` and `okapi-kafka` deliverers; pluggable `MessageDeliverer` API.
- `OutboxProcessor` with configurable `RetryPolicy` and delivery-result
  classification (`Success` / `RetriableFailure` / `PermanentFailure`).
- `okapi-spring-boot` autoconfiguration for stores, transports, scheduler, and purger.
- `okapi-exposed` integration (transaction runner, connection provider, validator).
- Concurrent processing via `FOR UPDATE SKIP LOCKED`.

[Unreleased]: https://github.com/softwaremill/okapi/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/softwaremill/okapi/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/softwaremill/okapi/releases/tag/v0.1.0
