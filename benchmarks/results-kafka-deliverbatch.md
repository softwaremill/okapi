# Kafka deliverBatch fire-flush-await — Results (KOJAK-73)

Measured 2026-05-04 on the same hardware as the April 2026 baseline (MacBook M3 Max,
JDK 25 LTS, Postgres 16 + Kafka 3.8.1 via Testcontainers, smoke-run JMH config:
`fork=1, warmup=1, iter=2, warmup=10s, measurement=15s`).

> ⚠️ **Statistical caveat:** numbers below come from a smoke-run config (`n=2` samples;
> `scoreError` in the raw JSON is `NaN`). The order-of-magnitude claim (13–41×) is
> physically credible (sequential `N×RTT` → `1×RTT`) but the precise multipliers are
> not statistically defensible until a full-config rerun (`fork=2, warmup=3, iter=5`).

## Headline numbers — Kafka throughput

| batchSize | Baseline (ms/op) | Post-optimization (ms/op) | **Improvement** |
|-----------|------------------|---------------------------|-----------------|
| 10        | 9.168            | 0.681                     | **13.5×**       |
| 50        | 8.665            | 0.268                     | **32.3×**       |
| 100       | 8.701            | 0.212                     | **41.0×**       |

Translated to msg/s:

| batchSize | Baseline | Post-optimization | Improvement |
|-----------|----------|-------------------|-------------|
| 10        | ~109     | **~1,468**        | 13.5×       |
| 50        | ~115     | **~3,731**        | 32.3×       |
| 100       | ~115     | **~4,717**        | 41.0×       |

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
  Post-optimization throughput scales with `batchSize` (1,468 → 3,731 → 4,717), proving that
  Kafka's internal record batching is now being exploited.
- **Sublinear scaling 50 → 100** (32× → 41× vs expected ~2× more). Indicates that DB UPDATE
  overhead per entry is now significant relative to the (now-fast) Kafka path. This is exactly
  what motivates the batch UPDATE optimization via JDBC `executeBatch` (KOJAK-75) — at small
  batch sizes the per-message DB cost was hidden by 9 ms Kafka RTT; with Kafka latency removed,
  the N individual UPDATE statements become the next bottleneck to attack.
- **batchSize=10 lowest gain (13.5×)** — at that batch size only 10 records can amortize
  one RTT, so the per-batch overhead (claimPending, transaction begin/commit, 10 UPDATEs) is
  proportionally larger.

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
   (`httpLatencyMs ∈ {20, 100}`): from ~33 / ~9 msg/s baseline to **~500-2,000 msg/s** range,
   depending on host/connection pool reuse.
2. **Batch UPDATE via JDBC `executeBatch`** (KOJAK-75). Now load-bearing: at `batchSize=100`
   the N individual UPDATE statements have become the dominant per-batch cost. Expected
   to shift `batchSize=100` Kafka throughput from ~4,700 toward the ~10,000 msg/s range.
3. **Concurrent processor fan-out** (KOJAK-77) — multi-threaded scheduler. Multiplies all
   of the above by N workers.
