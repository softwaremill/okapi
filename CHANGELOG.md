# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Until `1.0.0`, breaking changes may appear in any release and are flagged with **BREAKING** below.

## [Unreleased]

## [2.0.0] — 2026-07-31

### Changed (BREAKING)

- **`ExposedConnectionProvider`** now requires a `database: Database` constructor argument and
  reads the active transaction from `database.transactionManager.currentOrNull()` instead of the
  global `TransactionManager.currentOrNull()`. Previously, in a multi-database Exposed app, the
  provider would silently return whichever transaction happened to be innermost-active on the
  calling thread — regardless of which `Database` it belonged to — since every
  `OutboxStore`/`OutboxPublisher` operation routes through `withConnection`. Construct one
  instance per `Database`, matching `ExposedTransactionRunner` and
  `ExposedTransactionContextValidator`. ([#97](https://github.com/softwaremill/okapi/pull/97),
  [#96](https://github.com/softwaremill/okapi/issues/96))

### Fixed

- **`okapi-spring-boot` nondeterministically picked between `PostgresOutboxStore` and
  `MysqlOutboxStore`** when both `okapi-postgres` and `okapi-mysql` were on the classpath.
  `@Order(1)` / `@Order(2)` on the two nested store `@Configuration` classes now deterministically
  makes Postgres win, matching the documented behavior — previously nothing enforced an order and
  MySQL could silently win, applying its DDL/SQL against a Postgres database while the app looked
  healthy at startup. ([#94](https://github.com/softwaremill/okapi/pull/94),
  [#90](https://github.com/softwaremill/okapi/issues/90))
- **`publish()` failed on every call when the outbox `DataSource` bean was a
  `TransactionAwareDataSourceProxy`.** `SpringTransactionContextValidator` compared the raw proxy
  against `TransactionSynchronizationManager`'s bound resource, but Spring's
  `PlatformTransactionManager` always binds that resource under the raw target, not the proxy —
  despite `OutboxAutoConfiguration`'s startup check already verifying this exact wiring as
  correct. The validator now unwraps `DelegatingDataSource` chains (shared with the startup check
  via a new `DataSourceUnwrapping` utility) before comparing, and fails fast at construction time
  with an actionable message if a chain is unresolvable (cycle / null target).
  ([#95](https://github.com/softwaremill/okapi/pull/95),
  [#91](https://github.com/softwaremill/okapi/issues/91))
- **`okapi.metrics.enabled=false` was silently ignored.** `OkapiMicrometerAutoConfiguration` had
  no property-level opt-out, so setting the flag changed nothing — the only working route was
  excluding the whole auto-configuration via `spring.autoconfigure.exclude`. The flag now actually
  gates metrics initialization, with a startup warning logged when explicitly disabled.
  ([#93](https://github.com/softwaremill/okapi/pull/93),
  [#92](https://github.com/softwaremill/okapi/issues/92))
- **`NoClassDefFoundError` at startup when `okapi-micrometer` is absent from the classpath.**
  `OkapiMicrometerAutoConfiguration` now guards the whole auto-configuration with
  `MicrometerOutboxListener` in its class-level `@ConditionalOnClass`, so it is skipped entirely
  (not attempted and failed) when the module isn't present. ([#85](https://github.com/softwaremill/okapi/pull/85),
  [#84](https://github.com/softwaremill/okapi/issues/84))
- **`okapi-spring-boot` failed to start (`No setter found for property: interval`) unless
  `kotlin-reflect` happened to be dragged onto the classpath by an unrelated dependency.**
  `@ConfigurationProperties` binding for `OkapiProperties`, `OkapiMetricsProperties`,
  `OutboxProcessorProperties`, and `OutboxPurgerProperties` requires Kotlin reflection to resolve
  their primary constructor; `okapi-spring-boot` now declares `kotlin-reflect` directly instead of
  relying on it arriving transitively via `jackson-module-kotlin` (previously pulled in only by
  `okapi-http`/`okapi-kafka`). Java consumers and any app with a custom `MessageDeliverer` were the
  most exposed. ([#89](https://github.com/softwaremill/okapi/pull/89),
  [#88](https://github.com/softwaremill/okapi/issues/88))
- **`findOldestCreatedAt` threw a SQL syntax error (`WHERE status IN ()`) when called with an
  empty `statuses` set.** `PostgresOutboxStore` / `MysqlOutboxStore` now short-circuit to
  `emptyMap()` — the semantically correct result, consistent with the existing "statuses with no
  entries are omitted" contract. Latent bug, not reachable via the only production caller
  (`MicrometerOutboxMetrics`, which always passes a non-empty set).
  ([#98](https://github.com/softwaremill/okapi/pull/98),
  [#60](https://github.com/softwaremill/okapi/issues/60))

## [1.0.0] — 2026-07-28

First stable release. The public API now follows semantic versioning — breaking changes will
only ship in a new major version.

### Added

- **`OutboxSchedulerConfig.concurrency`** — fans out each scheduler tick to N parallel workers via
  a configurable `workerExecutorFactory` (fixed platform-thread pool by default; a virtual-thread
  factory is also provided). Each worker claims its own disjoint batch via `FOR UPDATE SKIP
  LOCKED`, so no app-level coordination is needed, and ticks never overlap. `concurrency = 1`
  (default) preserves the original single-worker, zero-overhead behavior. Benchmarked at
  3.6×–6.6× throughput scaling (concurrency 4→64); virtual threads showed no advantage over
  platform threads in that range. Wired into `okapi-spring-boot` via `okapi.processor.concurrency`.
  ([#73](https://github.com/softwaremill/okapi/pull/73))
- **`HttpMessageDeliverer.deliverBatch`** now fires all requests concurrently via
  `HttpClient.sendAsync()` instead of blocking sequentially on `HttpClient.send()` per entry —
  5×–15× throughput improvement depending on batch size and webhook latency.
  ([#77](https://github.com/softwaremill/okapi/pull/77))
- **`KafkaMessageDeliverer.deliverBatch`** — fire-flush-await pattern replaces N sequential
  blocking `producer.send().get()` round-trips with one batched `producer.flush()` — 13×–41×
  throughput improvement. ([#40](https://github.com/softwaremill/okapi/pull/40))
- **`OutboxStore.updateAfterProcessingBatch(entries)`** — batches a processed batch's DB write
  into a single JDBC `executeBatch()` call instead of N individual `updateAfterProcessing()`
  round-trips (~10× faster in isolation). Default implementation loops the existing per-entry
  method, so custom `OutboxStore` implementations keep working unmodified; `PostgresOutboxStore`
  / `MysqlOutboxStore` override it. ([#71](https://github.com/softwaremill/okapi/pull/71))

### Fixed

- **`okapi-spring-boot` startup crash with 2+ `PlatformTransactionManager` beans.**
  `OkapiMicrometerAutoConfiguration` now honours `okapi.transaction-manager-qualifier` and falls
  back gracefully (metrics run without a read-only snapshot transaction) instead of throwing
  `NoUniqueBeanDefinitionException`, matching how `OutboxAutoConfiguration` already resolved the
  PTM. ([#81](https://github.com/softwaremill/okapi/pull/81),
  [#80](https://github.com/softwaremill/okapi/issues/80))
- **`OutboxProcessor` constructor missing `@JvmOverloads`** — Java callers could no longer omit
  the `listener`/`clock` parameters and had to pass all four explicitly; restored.
  ([#75](https://github.com/softwaremill/okapi/pull/75),
  [#74](https://github.com/softwaremill/okapi/issues/74))

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

[Unreleased]: https://github.com/softwaremill/okapi/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/softwaremill/okapi/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/softwaremill/okapi/compare/v0.3.0...v1.0.0
[0.3.0]: https://github.com/softwaremill/okapi/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/softwaremill/okapi/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/softwaremill/okapi/releases/tag/v0.1.0
