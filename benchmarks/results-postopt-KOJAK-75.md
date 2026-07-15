# Batch UPDATE via JDBC executeBatch — Results (KOJAK-75)

Measured on the same machine/config as [`results-kafka-deliverbatch.md`](results-kafka-deliverbatch.md):
JDK 21 LTS, Postgres 16 via Testcontainers, full JMH config `fork=2, warmup=3 × 10s, iter=5 × 30s`
(n=10 samples per benchmark).

## Headline numbers — UPDATE phase only, 1000 entries

`OutboxStoreUpdateBatchBenchmark` isolates the persistence step: 1000 entries are claimed and
pre-computed as already-processed (`toDelivered`) in `@Setup(Level.Invocation)`, then each
`@Benchmark` method times only the write-back — either N individual
`updateAfterProcessing()` calls or a single `updateAfterProcessingBatch()` `executeBatch()` call.

| Method              | ms/op (per entry)     | Total for 1000 entries | **Improvement** |
|---------------------|------------------------|-------------------------|------------------|
| `individualUpdates`  | 0.1876 ± 0.0080       | ~187.6 ms               | baseline         |
| `batchUpdate`        | 0.0183 ± 0.0020       | ~18.3 ms                | **10.2×**        |

Raw JSON: [`outbox-store-update-batch.json`](outbox-store-update-batch.json).

## What changed

- `OutboxStore.updateAfterProcessingBatch(entries)` — new method, default implementation loops
  `updateAfterProcessing()` (one JDBC roundtrip per entry, unchanged behavior for stores that
  don't override it).
- `PostgresOutboxStore` / `MysqlOutboxStore` override it with a single `PreparedStatement`
  reused across all entries via `addBatch()` / `executeBatch()`, touching only the 5 mutable
  columns (`status`, `updated_at`, `retries`, `last_attempt`, `last_error`) instead of the full
  11-column upsert used by `persist()`.
- `OutboxProcessor.processNext()` now calls `store.updateAfterProcessingBatch(processed)` once
  per drained batch instead of looping `updateAfterProcessing()` per entry.

## Reading the numbers

- 10.2× on localhost Postgres (Testcontainers, ~sub-ms RTT) is already above the ticket's
  50-70% *relative* improvement estimate — expected, since that estimate was for the **whole**
  Kafka-delivery batch (deliver + update), whereas this benchmark isolates the update phase
  alone, which is exactly where all the savings land.
- Real-world gains should be **larger**, not smaller: this machine's Postgres RTT is well under
  1ms, so the "N roundtrips" baseline is already best-case. Against a real network-hosted
  Postgres (1-5 ms RTT), `individualUpdates` would scale roughly linearly with N × RTT while
  `batchUpdate` stays close to one roundtrip — the gap should widen further.
- Error bars (`±` above) are small relative to the score for both benchmarks, so the 10.2×
  figure is not noise.

## Caveat on this run's full-pipeline numbers

This run also re-executed `KafkaThroughputBenchmark` / `HttpThroughputBenchmark` /
`DelivererMicroBenchmark` as part of the same `:okapi-benchmarks:jmh` invocation (a benchmark
filter that was expected to narrow the run to `OutboxStoreUpdateBatchBenchmark` alone was not
honored by the `me.champeau.jmh` plugin). The full run's wall clock was affected by an
environment interruption partway through (host suspend/resume), so the `KafkaThroughputBenchmark`
/ `HttpThroughputBenchmark` numbers from `results.json` in this run are **not** directly
comparable to the [KOJAK-73 baseline](results-kafka-deliverbatch.md) and should be re-measured
in a clean run before drawing conclusions about end-to-end throughput improvement. Only the
`OutboxStoreUpdateBatchBenchmark` numbers above — captured within that same run but validated by
tight, consistent error bars across both forks — are reported here.

## Verification context

- Unit tests: `OutboxStoreTest` (default `updateAfterProcessingBatch` loop behavior),
  `OutboxProcessorTest` (asserts `processNext()` calls the batch method exactly once instead of
  per-entry).
- Integration tests: `OutboxStoreContractTests` — mixed final statuses (DELIVERED + PENDING
  retry + FAILED) persisted via one `updateAfterProcessingBatch()` call, run against both
  Postgres and MySQL.
- ktlint clean, full `./gradlew build` green (core, postgres, mysql, micrometer,
  integration-tests, spring-boot).
