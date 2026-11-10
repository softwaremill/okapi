# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Until `1.0.0`, breaking changes may appear in any release and are flagged with **BREAKING** below.

## [Unreleased]

### Added

- **`x-outbox-id` header on every delivery.** Both `KafkaMessageDeliverer` and `HttpMessageDeliverer`
  now attach the entry's UUID under `OutboxHeaders.OUTBOX_ID` (`okapi-core`, so consumers can
  reference the name without depending on a transport module). The value is stable across retries of
  an entry, giving consumers something to deduplicate redeliveries on. okapi sets it **last**, so it
  overrides a header of the same name supplied via `DeliveryInfo` — replaced outright over HTTP;
  appended after yours in Kafka, whose headers are multi-valued, which is why consumers must read it
  with `lastHeader(...)`. It does not deduplicate at the `publish()` level: two `publish()` calls for
  the same business event are two entries with two ids. Previously the README stated that okapi did
  not send the `OutboxId`; that is no longer the case, and the "Deduplicating on `x-outbox-id`"
  section documents the recipe and both caveats. (KOJAK-78)

### Fixed

- **MySQL: the processor no longer blocks `publish()` for the duration of delivery.** Under MySQL's
  default `REPEATABLE READ`, the claim's `FOR UPDATE SKIP LOCKED` took gap locks on the claim index;
  with a backlog smaller than the batch, the gap ran to the end of the claimed type's range, exactly
  where every `publish()` of that type inserts, so business transactions waited for the whole
  HTTP/Kafka delivery and could fail with `Lock wait timeout exceeded`. On MySQL and MariaDB the scheduler
  transaction now runs at `READ COMMITTED`, which takes no gap locks:
  - Spring Boot: the autoconfigured `TransactionRunner` pins `READ COMMITTED` when the store is
    `MysqlOutboxStore` and its `TransactionTemplate` leaves isolation at `DEFAULT`. It uses a copy,
    so a user's or Boot's template bean is not mutated and its timeout/propagation/read-only
    settings carry over; a template with an explicit isolation level is still used as-is. Pinning is
    skipped, with a startup WARN, for PTMs that reject custom isolation levels — a
    `JtaTransactionManager`, or a `JpaTransactionManager` without the Hibernate/EclipseLink
    `JpaDialect` — since every tick would otherwise throw `InvalidIsolationLevelException`.
  - `ExposedTransactionRunner(database)` uses `READ COMMITTED` when the database's dialect is MySQL or
    MariaDB. A new `ExposedTransactionRunner(database, transactionIsolation)` constructor sets the
    level explicitly (`null` = the database default).

  Other stores and databases are unchanged: Postgres already defaults to `READ COMMITTED`. MySQL at
  `READ COMMITTED` needs `binlog_format=ROW` or `MIXED` (see the README). (#129)
- **A worker no longer marks rows it cannot deliver as `FAILED`.** Previously every worker claimed
  all `PENDING` rows, so a worker without a deliverer for a row's type turned it into a permanent
  failure. Each worker now claims only its registered delivery types, in at most two queries per
  batch. Workers on an older version still claim every type, so publish a newly introduced type only
  after all old processors are upgraded or stopped. Rows already marked `FAILED` are not retried
  automatically. Cross-type claim order is no longer globally oldest first: the processor rotates
  the starting type, and any leftover batch capacity goes to the other types alphabetically.
  ([#116](https://github.com/softwaremill/okapi/issues/116))
- **One unpersistable `lastError` no longer poisons its whole batch.** All results of a batch are
  written in one update inside the claim transaction, so a `last_error` value the database rejected
  rolled back the entire batch: entries that had already been delivered went back to `PENDING` with
  `retries` unchanged and were redelivered on every tick, indefinitely (three times per tick under
  Exposed's default retry). Two such values were easy to hit: an HTTP error body over 64 KB
  (MySQL `TEXT` overflow, `ERROR 1406`) and a NUL character (refused by Postgres).
  `OutboxEntry.retry`/`toFailed` now strip NULs and truncate to `OutboxEntry.MAX_LAST_ERROR_LENGTH`
  (4096 chars, never splitting a surrogate pair), so every transport is covered. Both built-in
  stores apply the same `OutboxEntry.sanitizeLastError` again when writing, so an entry built with
  `copy(lastError = ...)` is covered too; custom stores should call it before writing `last_error`.
  (#130)
- **`HttpMessageDeliverer` no longer buffers whole response bodies, or waits on them forever.**
  Success bodies are discarded, and only the first `HttpMessageDeliverer.MAX_ERROR_BODY_BYTES`
  (2 KB) of an error body is kept for `lastError`. At most `MAX_DRAINED_BODY_BYTES` (64 KB) of any
  body is read: up to that the rest is drained so the connection is reused, past it the body is
  cancelled. Each exchange, body included, must also finish within `EXCHANGE_TIMEOUT` (30 s) or it
  is cancelled as a `RetriableFailure`; the request timeout only covers waiting for the headers.
  Previously every response was read fully into heap, and a server dripping its body held delivery,
  and with it the scheduler's claim transaction, open indefinitely. (#130)

### Changed

- **`ExposedTransactionRunner` on MySQL/MariaDB now overrides `DatabaseConfig.defaultIsolationLevel`.**
  Scheduler transactions run at `READ COMMITTED` even when the application configured another default
  isolation (e.g. `SERIALIZABLE`) for its `Database`; business transactions keep that default. Pass
  the level you want to `ExposedTransactionRunner(database, transactionIsolation)`, or `null` to keep
  the database default, but note that `REPEATABLE READ` or stricter brings back the `publish()`
  blocking described under *Fixed*. (#129)
- **`CompositeMessageDeliverer.deliverBatch`** now dispatches transport groups concurrently — one
  virtual thread per group, with the last group run on the calling thread — instead of blocking on
  each group in turn. A batch spanning N transports costs ~`max(Tᵢ)` rather than `sum(Tᵢ)`; a batch
  with a single delivery type takes a fast path that starts no thread and allocates no executor, so
  homogeneous workloads are unchanged. `MessageDeliverer` implementations must therefore be
  thread-safe and must not depend on caller thread-locals (MDC, `TransactionSynchronizationManager`,
  security context). The transaction scope is unchanged — the scheduler still wraps the whole
  `OutboxProcessor.processNext` cycle (claim, deliver, update) in one transaction, so it remains
  open across delivery — but a transport group dispatched to a virtual thread no longer inherits
  the caller's transaction-bound resources, so a deliverer that implicitly joined the outbox
  transaction (e.g. via Spring's `DataSourceUtils`) now gets a fresh connection. Deliverers that
  cannot satisfy that can restore the previous behaviour with the new
  `TransportDispatch.SEQUENTIAL` constructor argument, exposed in `okapi-spring-boot` as
  `okapi.processor.transport-dispatch=sequential`. For a heterogeneous batch, parallel dispatch
  also shortens how long the transaction stays open, from `sum(Tᵢ)` to ~`max(Tᵢ)`. (KOJAK-81)
- **`CompositeMessageDeliverer` now upholds `deliverBatch`'s "must not throw" contract even when a
  transport does not.** A deliverer that throws, or that returns no result for some of its entries,
  previously propagated the exception (or an `IllegalStateException` from the result-assembly step)
  and aborted the whole batch; it now fails only its own entries, as `RetriableFailure`, leaving the
  other transports' results intact. Such entries are retried under the configured `RetryPolicy`
  rather than rolled back and re-claimed indefinitely. (KOJAK-81)

### Changed (BREAKING)

- **The scheduler drains the backlog within a tick instead of processing one batch per worker.**
  Each worker used to process exactly one batch per tick, capping throughput at about
  `concurrency × batchSize / interval`, under 10 messages/s with the defaults, so any sustained
  higher publish rate grew the backlog without bound. A worker now keeps processing batches, each in
  its own transaction, while the previous one was full, up to the new
  `OutboxSchedulerConfig.maxBatchesPerTick` (default 1000, Spring property
  `okapi.processor.max-batches-per-tick`). A delivery type with nothing delivered in a batch (all
  rescheduled for retry or failed for good) is skipped for the rest of the tick, so a failing or
  misconfigured downstream still gets one batch per tick while other types keep draining, and the
  tick ends when every type is skipped. Batches of a tick claim only entries due when the tick
  started, so a retry waits for the next tick even with a zero backoff. The poll interval now only
  sets the idle polling rate: a healthy downstream receives a backlog as fast as okapi can deliver
  it, and `onBatchProcessed` / `okapi.batch.duration` fire more often per tick.
  `maxBatchesPerTick = 1` restores the previous behaviour. `OutboxSchedulerConfig` and
  `OutboxProcessorProperties` gained a constructor parameter: `@JvmOverloads` keeps the previous
  Java constructors, but compiled Kotlin code that calls `copy(...)` or a constructor relying on
  default arguments fails with `NoSuchMethodError` until recompiled.
  ([#132](https://github.com/softwaremill/okapi/issues/132))
- **Retries back off exponentially instead of firing on every poll (new migration `004__retry_backoff`).**
  A retriable failure used to be retried on the very next tick, so with the defaults (`interval`
  1s, `max-retries` 5) a message was `FAILED` — terminally — after about six seconds of downstream
  outage, and failing rows, always the oldest, were re-claimed first on every tick. Each entry now
  carries `nextAttemptAt` (column `next_attempt_at`); a retriable failure sets it `RetryPolicy`'s
  backoff into the future, and the processor claims only due entries, ordered by
  `(delivery_type, next_attempt_at, id)` through a new index that replaces 002's. The default backoff
  is 10s, doubling, capped at 10m, with 20% jitter, which covers about five minutes of outage before
  `FAILED`. New Spring properties `okapi.processor.initial-backoff`, `backoff-multiplier`,
  `max-backoff` and `backoff-jitter`. API changes: `RetryPolicy` gained `initialBackoff`,
  `backoffMultiplier`, `maxBackoff` and `jitter` (all defaulted, `@JvmOverloads`) and `backoff(retry)`;
  `OutboxEntry` gained `nextAttemptAt` (defaults to `createdAt`) and `retry` an optional
  `nextAttemptAt`; `RouteAwareOutboxStore.claimPending` takes the current time (`now`); and
  `OutboxProcessorProperties` gained four parameters. `OutboxEntry`'s constructor is `@JvmOverloads`,
  so Java callers can still omit `nextAttemptAt`. As with every data-class change, Kotlin code
  calling `copy(...)` or default-argument constructors of these classes must be recompiled; custom
  `RouteAwareOutboxStore`s must filter on and write `next_attempt_at`. The legacy
  `OutboxStore.claimPending(limit)` ignores the backoff. Tests or callers that relied on immediate
  retries need `RetryPolicy(maxRetries, initialBackoff = Duration.ZERO, maxBackoff = Duration.ZERO)`.
  ([#131](https://github.com/softwaremill/okapi/issues/131))
- **`OutboxProcessorListener` events are emitted after the transaction commits.** `OutboxProcessor`
  notified the listener right after writing a batch, still inside the scheduler's transaction, so a
  failed commit (connection loss, serialization failure, timeout) left `okapi.entries.delivered` /
  `okapi.entries.failed` incremented for rows that rolled back to `PENDING`, which were then counted
  again on redelivery, and a custom listener could act on state that was never persisted.
  `OutboxProcessor` now has `processBatch(limit)`, which claims, delivers and persists without
  notifying and returns a `ProcessedBatch`, and `publishEvents(batch)`. `OutboxScheduler` runs
  `processBatch` in the transaction and `publishEvents` after it returns, so under the scheduler the
  listener runs after the commit, outside the transaction, and not at all for a batch whose commit
  failed. A listener that relied on running inside the processing transaction (e.g. to write to the
  database atomically with the status update) no longer can. `onBatchProcessed`'s duration, and with
  it `okapi.batch.duration`, now includes the commit (and excludes the listener callbacks); empty
  polls are still reported. Counters now count committed status changes rather than delivery
  attempts. `TransactionRunner` now documents that `runInTransaction` must have committed when it
  returns, and `publishEvents` throws if a batch is published twice.
  `processNext(limit)` keeps notifying at once, for callers that drive the processor themselves.
  ([#137](https://github.com/softwaremill/okapi/issues/137))
- **`OutboxProcessorProperties` gained a `transportDispatch` constructor parameter** (see the
  `CompositeMessageDeliverer` entry above). `@JvmOverloads` preserves the previous four-argument
  JVM constructor, so Java callers are unaffected, but adding a property to a Kotlin `data class`
  necessarily changes the generated `copy`/`copy$default` and default-argument constructor
  signatures. Kotlin code compiled against an earlier release and not recompiled will fail with
  `NoSuchMethodError` if it calls `copy(...)` on this class or constructs it using default
  arguments — recompile consumers against this release. Binding from `application.yml` /
  `application.properties` is unaffected, as is `CompositeMessageDeliverer`, whose new parameter
  is covered by `@JvmOverloads` for previously compiled Kotlin and Java callers alike.
  ([#113](https://github.com/softwaremill/okapi/pull/113))
- **`ExposedConnectionProvider`** now requires a `database: Database` constructor argument and
  reads the active transaction from `database.transactionManager.currentOrNull()` instead of the
  global `TransactionManager.currentOrNull()`. Previously, in a multi-database Exposed app, the
  provider would silently return whichever transaction happened to be innermost-active on the
  calling thread — regardless of which `Database` it belonged to — since every
  `OutboxStore`/`OutboxPublisher` operation routes through `withConnection`. Construct one
  instance per `Database`, matching `ExposedTransactionRunner` and
  `ExposedTransactionContextValidator`. ([#96](https://github.com/softwaremill/okapi/issues/96))
- **`OutboxProcessor` requires a `RouteAwareOutboxStore`.** The processor now claims only the
  delivery types its `OutboxEntryProcessor` handles, through the new
  `RouteAwareOutboxStore.claimPending(deliveryTypes, limit)`; `PostgresOutboxStore` and
  `MysqlOutboxStore` implement it. A custom `OutboxStore`, or a hand-written wrapper around a
  built-in store, must implement `RouteAwareOutboxStore` too: otherwise `processNext` throws, and
  Spring autoconfiguration fails at startup while the processor is enabled. `claimPending(limit)`
  stays on `OutboxStore` for direct callers. ([#120](https://github.com/softwaremill/okapi/pull/120))
- **`OutboxEntryProcessor.process`/`processBatch` throw `IllegalStateException` for an entry whose
  delivery type has no registered deliverer.** Previously `CompositeMessageDeliverer` returned a
  `PermanentFailure` and the entry was marked `FAILED`. Inside `OutboxProcessor` the exception rolls
  the transaction back and the row stays `PENDING`; direct callers must handle the exception.
  ([#120](https://github.com/softwaremill/okapi/pull/120))
- **New migration `002__route_aware_claim_index`** adds a `(status, delivery_type, created_at, id)`
  index on MySQL and a partial `(delivery_type, created_at, id) WHERE status = 'PENDING'` index on
  PostgreSQL. The bundled Liquibase changelogs apply it automatically. If you manage the schema
  yourself, apply it before starting updated workers: the MySQL claim uses `FORCE INDEX` and fails
  without it, and the PostgreSQL script must run outside a transaction (`CREATE INDEX CONCURRENTLY`).
  ([#120](https://github.com/softwaremill/okapi/pull/120))
- **MySQL: timestamp columns are `DATETIME(6)` in UTC (new migration `003__datetime_columns`).**
  `created_at`, `updated_at` and `last_attempt` were `TIMESTAMP(6)`, which cannot hold anything
  after `2038-01-19 03:14:07 UTC`; from then on every `publish()` would fail. The migration converts
  existing values without shifting them (it pins the session to UTC for the conversion) and drops the
  `CURRENT_TIMESTAMP` column defaults, which okapi never relied on. `MysqlOutboxStore` now binds and
  reads these columns as UTC `LocalDateTime`, so stored values no longer depend on the JVM time zone,
  the session `time_zone` or Connector/J's `connectionTimeZone`/`preserveInstants`. Stop every 1.x
  instance, publishers included, before the migration runs (1.x writes these columns in the JVM
  zone), and drain the outbox first if your JVMs and MySQL session ran in different zones: rows 1.x
  wrote then were stored shifted by the difference. The migration copies the table, waits at most
  30 s for a metadata lock (then fails and retries on the next start) and blocks writes while it
  copies. Anything else reading these columns directly must now treat them as UTC wall-clock values.
  See UPGRADING.md.
  ([#138](https://github.com/softwaremill/okapi/issues/138))
- **PostgreSQL: timestamp columns are `TIMESTAMPTZ` (new migration `003__timestamptz_columns`).**
  `created_at`, `updated_at` and `last_attempt` were `TIMESTAMP WITHOUT TIME ZONE`, and pgjdbc
  renders a `java.sql.Timestamp` in the JVM default zone, so each row held the writing JVM's local
  wall-clock time. In a fleet whose JVMs ran in different zones, or across a DST fall-back hour,
  claim ordering, the lag gauge and the purger's retention cutoff were off by hours. The migration
  converts existing values using the session `TimeZone`, which pgjdbc sets to the JVM default zone,
  so the bundled Liquibase changelog keeps them correct when it runs inside the application; apply
  it by hand only inside a transaction and after `SET TIME ZONE '<your JVM zone>'`.
  `PostgresOutboxStore` now binds and reads these columns as `OffsetDateTime`. The migration takes an
  `ACCESS EXCLUSIVE` lock (waiting at most 30 s for it, then failing and retrying on the next start)
  and, unless the session zone is UTC, rewrites the table. 1.x instances keep working against the
  migrated columns, apart from one failed poll per pooled connection. Rows written during a
  fall-back hour by a non-UTC JVM were already ambiguous and may stay an hour off. See UPGRADING.md.
  ([#135](https://github.com/softwaremill/okapi/issues/135))

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

Existing deployments upgrading directly from 0.2.x to 1.0.0 must complete the steps in
[UPGRADING.md](UPGRADING.md) before starting the new version.

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

[Unreleased]: https://github.com/softwaremill/okapi/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/softwaremill/okapi/compare/v0.3.0...v1.0.0
[0.3.0]: https://github.com/softwaremill/okapi/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/softwaremill/okapi/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/softwaremill/okapi/releases/tag/v0.1.0
