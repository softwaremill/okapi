# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Until `1.0.0`, breaking changes may appear in any release and are flagged with **BREAKING** below.

## [Unreleased]

## [0.3.0] — 2026-06-08

### Changed (BREAKING)

- **Domain table renamed `outbox` → `okapi_outbox`** (indexes `idx_outbox_*` →
  `idx_okapi_outbox_*`). okapi now owns a prefixed table, so a pre-existing `outbox`
  no longer collides. Not configurable. ([#37](https://github.com/softwaremill/okapi/issues/37))
- **Liquibase tracking tables default to `okapi_databasechangelog` /
  `okapi_databasechangeloglock`** instead of sharing the app's defaults; override via
  the new properties to keep the old layout. ([#37](https://github.com/softwaremill/okapi/issues/37))
- **okapi's Liquibase migrations consolidated into a single
  `001__create_okapi_outbox_table.sql` per database.** Resulting schema is unchanged,
  but the `outbox:001` checksum changed — upgraders must start on a fresh okapi schema
  or clear okapi's rows from `okapi_databasechangelog`.
  ([#50](https://github.com/softwaremill/okapi/pull/50))
- **`OutboxScheduler` / `OutboxPurger` (okapi-core) now require a non-null
  `TransactionRunner`.** The old nullable default silently ran non-transactionally,
  letting `FOR UPDATE SKIP LOCKED` drop its lock under JDBC auto-commit and deliver
  entries more than once. Spring Boot users unaffected; direct users (Ktor, manual
  wiring, Java/Kotlin) must supply one. ([#51](https://github.com/softwaremill/okapi/issues/51))
- **`OutboxProcessorScheduler` / `OutboxPurgerScheduler` now require a non-null
  `TransactionRunner`** (was a nullable `TransactionTemplate?`). Spring autoconfig
  derives it from any `PlatformTransactionManager`; direct constructor users must pass
  `SpringTransactionRunner(template)` or a thin wrapper. ([#49](https://github.com/softwaremill/okapi/pull/49))
- **`PostgresOutboxStore` / `MysqlOutboxStore` no longer take a `clock` parameter** —
  it became unused after the lag-gauge fix ([#58](https://github.com/softwaremill/okapi/pull/58)).
  Drop the second constructor argument; Spring Boot users unaffected.
  ([#59](https://github.com/softwaremill/okapi/pull/59))
- **`okapi-spring-boot` autoconfig fails fast when it cannot verify the
  PlatformTransactionManager↔outbox-DataSource binding** in a multi-DataSource context
  with no `okapi.transaction-manager-qualifier` set. Name the PTM via that qualifier, or
  supply an explicit `@Bean TransactionRunner` to bypass. ([#49](https://github.com/softwaremill/okapi/pull/49))

### Added

- **`MessageDeliverer.deliverBatch(entries)`** — batch-aware delivery method with a
  sequential default impl (loops `deliver()`, preserving order and per-entry result
  classification). Existing deliverers need no change; transports can override it for
  concurrent I/O, and `CompositeMessageDeliverer` routes batches by delivery type.
  ([#35](https://github.com/softwaremill/okapi/pull/35))
- `okapi.liquibase.changelog-table` / `okapi.liquibase.changelog-lock-table` — Spring Boot
  properties to override okapi's Liquibase tracking-table names (defaults
  `okapi_databasechangelog` / `okapi_databasechangeloglock`).

### Fixed

- **HTTP delivery exception classification.** `HttpMessageDeliverer` previously caught
  every exception as `RetriableFailure`, so corrupt delivery metadata or an unknown
  service wasted the whole retry budget before being marked `FAILED` instead of failing
  fast. `JsonProcessingException` and other non-IO errors (malformed URI, unknown
  service) are now `PermanentFailure`; `IOException` / `InterruptedException` stay
  retriable. ([#44](https://github.com/softwaremill/okapi/pull/44))
- **`okapi.transaction-manager-qualifier` is now honoured even when
  `TransactionAutoConfiguration` registers a unique `TransactionTemplate`.** Previously the
  qualifier was silently ignored in multi-PTM setups, defaulting to the @Primary PTM. Rule
  is now: explicit qualifier > auto-wired TT. ([#49](https://github.com/softwaremill/okapi/pull/49))
- **`okapi-kafka` now exposes `kafka-clients` and `okapi-core` as `api` dependencies.**
  `KafkaMessageDeliverer`'s public constructor takes `Producer<String, String>`, so those
  types belong on the consumer's compile classpath transitively — no more adding
  `kafka-clients` by hand or hitting surprising `okapi-core` classpath failures.
  ([#47](https://github.com/softwaremill/okapi/pull/47))
- **Startup `NoClassDefFoundError` on Spring Boot 3.5.x without `liquibase-core`** (e.g.
  Flyway-only apps) — okapi's Liquibase beans are now guarded by class-level
  `@ConditionalOnClass(SpringLiquibase)`. Also stops okapi's `SpringLiquibase` bean from
  shadowing the host application's own changelog — okapi's auto-config is now ordered
  after Spring Boot's `LiquibaseAutoConfiguration`.
  ([#42](https://github.com/softwaremill/okapi/pull/42),
  [#38](https://github.com/softwaremill/okapi/issues/38))
- **`okapi-micrometer` auto-config ordering on Spring Boot 3.5.x.** `@AutoConfigureAfter`
  now lists both the 3.5.x and 4.0.x metrics-package locations, so the listener / metrics /
  refresher are no longer silently skipped when `MeterRegistry` registers later.
  ([#41](https://github.com/softwaremill/okapi/pull/41))
- **`OutboxPurger` error log preserves partial-batch progress.** A mid-loop failure now
  reports how many entries / batches were already purged this tick, so operators can tell
  an early outage from a late transient hiccup. ([#55](https://github.com/softwaremill/okapi/pull/55))

### Migration from 0.2.x

Breaking — existing deployments must act before the first `0.3.0` startup. Full SQL is in
the README: [Database migrations § Upgrading from 0.2.x](README.md#upgrading-from-02x).
Rename the domain table in place (no opt-out), and either adopt the new Liquibase
tracking-table names or override them back to the legacy ones.

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

[Unreleased]: https://github.com/softwaremill/okapi/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/softwaremill/okapi/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/softwaremill/okapi/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/softwaremill/okapi/releases/tag/v0.1.0
