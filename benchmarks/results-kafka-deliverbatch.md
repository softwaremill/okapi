# Kafka deliverBatch fire-flush-await — Results (KOJAK-73)

Measured 2026-05-14 on the same hardware as the April 2026 baseline (MacBook M3 Max,
JDK 21 LTS, Postgres 16 + Kafka 3.8.1 via Testcontainers, full JMH config:
`fork=2, warmup=3 × 10s, iter=5 × 30s` — n=10 samples per benchmark).

## Headline numbers — Kafka throughput

| batchSize | Baseline (ms/op) | Post-optimization (ms/op) | **Improvement** |
|-----------|------------------|---------------------------|-----------------|
| 10        | 9.168            | 0.548 ± 0.021             | **16.7×**       |
| 50        | 8.665            | 0.239 ± 0.008             | **36.3×**       |
| 100       | 8.701            | 0.195 ± 0.004             | **44.6×**       |

Translated to msg/s (≥1000 ops per drain × `@OperationsPerInvocation(1000)`):

| batchSize | Baseline   | Post-optimization | Improvement |
|-----------|------------|-------------------|-------------|
| 10        | ~109 msg/s | **~1,825 msg/s**  | 16.7×       |
| 50        | ~115 msg/s | **~4,184 msg/s**  | 36.3×       |
| 100       | ~115 msg/s | **~5,128 msg/s**  | 44.6×       |

Raw JSON: [`kafka-deliverbatch.json`](kafka-deliverbatch.json).

## What changed

`KafkaMessageDeliverer.deliverBatch` now uses fire-flush-await:
1. **Fire** — call `producer.send()` for every entry (non-blocking; records go to producer's internal buffer)
2. **Flush** — single `producer.flush()` call drives all queued records to the broker in one batched network round-trip (bypasses `linger.ms`)
3. **Await** — `Future.get()` per entry returns immediately because completion is settled by `flush()`

Previously, each entry incurred a full `producer.send().get()` round-trip sequentially. With ~9 ms localhost Kafka RTT (`acks=all`), 1000 entries × 9 ms = ~9 s regardless of `batchSize`.

## Reading the table

- **`batchSize` is now load-bearing.** Pre-optimization throughput was flat across `batchSize`
  values (109 → 115 → 115 msg/s) — confirming the bottleneck was per-record blocking I/O.
  Post-optimization throughput scales with `batchSize` (1,825 → 4,184 → 5,128), proving that
  Kafka's internal record batching is now being exploited.
- **Sublinear scaling 50 → 100** (36× → 45× vs expected ~2× more). Indicates that DB UPDATE
  overhead per entry is now significant relative to the (now-fast) Kafka path. This is exactly
  what motivates the batch UPDATE optimization via JDBC `executeBatch` (KOJAK-75) — at small
  batch sizes the per-message DB cost was hidden by 9 ms Kafka RTT; with Kafka latency removed,
  the N individual UPDATE statements become the next bottleneck to attack.
- **batchSize=10 lowest gain (16.7×)** — at that batch size only 10 records can amortize
  one RTT, so the per-batch overhead (claimPending, transaction begin/commit, 10 UPDATEs) is
  proportionally larger.
- **Variance is tight.** All Kafka throughput error bars are <5% of the score — confidence
  intervals are narrow enough to defend the multipliers as published.

## HTTP throughput (companion benchmark)

For context, the corresponding HTTP throughput numbers from the same run (still sync sequential
delivery — KOJAK-74 will apply parallel `sendAsync`):

| batchSize | latency 0 ms     | latency 20 ms     | latency 100 ms    |
|-----------|------------------|-------------------|-------------------|
| 10        | 0.665 ms/op      | 26.484 ms/op      | 110.354 ms/op     |
| 50        | 0.320 ms/op      | 25.767 ms/op      | 108.636 ms/op     |
| 100       | 0.288 ms/op      | 27.962 ms/op      | 107.986 ms/op     |

The flat per-message latency at `latencyMs=20/100` confirms HTTP is fully sequential: each
record waits for the previous response before the next request goes out. That is the gap KOJAK-74
addresses.

## Verification context

- Unit tests: `KafkaMessageDelivererBatchTest` covers empty input, all-success ordering,
  single flush call (verified via flush counter), synchronous send exception (Permanent +
  Retriable variants), and future-based async exception (driven via `MockProducer` override
  that completes/errors per-position inside flush).
- Integration tests in `okapi-integration-tests` continue to pass with real Postgres + Kafka.
- ktlint clean, configuration cache reuses across modules.

## What's next

1. **HTTP `deliverBatch`** (KOJAK-74) — analogous fire-all-await for HTTP via parallel
   `httpClient.sendAsync`. Expected impact at realistic webhook latency
   (`httpLatencyMs ∈ {20, 100}`): from ~38 / ~9 msg/s baseline to **~500-2,000 msg/s** range,
   depending on host/connection pool reuse.
2. **Batch UPDATE via JDBC `executeBatch`** (KOJAK-75). Now load-bearing: at `batchSize=100`
   the N individual UPDATE statements have become the dominant per-batch cost. Expected
   to shift `batchSize=100` Kafka throughput from ~5,100 toward the ~10,000 msg/s range.
3. **Concurrent processor fan-out** (KOJAK-77) — multi-threaded scheduler. Multiplies all
   of the above by N workers.
