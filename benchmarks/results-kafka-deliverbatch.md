# Kafka deliverBatch fire-flush-await — Results (KOJAK-73)

Measured on MacBook M3 Max, JDK 21 LTS, Postgres 16 + Kafka 3.8.1 via Testcontainers,
full JMH config: `fork=2, warmup=3 × 10s, iter=5 × 30s` — n=10 samples per benchmark.

## Headline numbers — Kafka throughput

| batchSize | Baseline (ms/op) | Optimized (ms/op) | **Improvement** |
|-----------|------------------|-------------------|-----------------|
| 10        | 9.168            | 0.559 ± 0.029     | **16.4×**       |
| 50        | 8.665            | 0.242 ± 0.007     | **35.8×**       |
| 100       | 8.701            | 0.193 ± 0.004     | **45.1×**       |

Translated to msg/s (`@OperationsPerInvocation(1000)`):

| batchSize | Baseline   | Optimized        | Improvement |
|-----------|------------|------------------|-------------|
| 10        | ~109 msg/s | **~1,790 msg/s** | 16.4×       |
| 50        | ~115 msg/s | **~4,132 msg/s** | 35.8×       |
| 100       | ~115 msg/s | **~5,181 msg/s** | 45.1×       |

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
  Post-optimization throughput scales with `batchSize` (1,790 → 4,132 → 5,181), proving that
  Kafka's internal record batching is now being exploited.
- **Sublinear scaling 50 → 100** (36× → 45× vs expected ~2× more). Indicates that DB UPDATE
  overhead per entry is now significant relative to the (now-fast) Kafka path. This is exactly
  what motivates the batch UPDATE optimization via JDBC `executeBatch` (KOJAK-75) — at small
  batch sizes the per-message DB cost was hidden by 9 ms Kafka RTT; with Kafka latency removed,
  the N individual UPDATE statements become the next bottleneck to attack.
- **batchSize=10 lowest gain (16.4×)** — at that batch size only 10 records can amortize
  one RTT, so the per-batch overhead (claimPending, transaction begin/commit, 10 UPDATEs) is
  proportionally larger.
- **All Kafka throughput error bars <5% of score** — confidence intervals are narrow enough
  to defend the multipliers. Numbers independently reproduced across two separate runs.

## Code overhead microbenchmarks

`DelivererMicroBenchmark` measures the cost of `deliver()` with I/O mocked away — useful as
a regression check on the library code itself (Jackson deserialize + record construction +
exception classification + result wrapping).

| Benchmark    | Score                  | Notes                                            |
|--------------|------------------------|--------------------------------------------------|
| kafkaDeliver | 2,324,098 ± 19,575 ops/s | ~430 ns per `deliver()` (MockProducer, no I/O) |
| httpDeliver  | 11,545 ± 149 ops/s     | ~87 µs per `deliver()` (WireMock localhost)      |

In production these numbers are dominated by network I/O (~10 ms localhost Kafka, ~5-50 ms
HTTP webhook), so the library overhead is <1% of real-world per-message cost. Microbench is
there to catch regressions if anyone refactors `KafkaMessageDeliverer`/`HttpMessageDeliverer`
and accidentally adds allocations or expensive work to the hot path.

## HTTP throughput (companion benchmark)

HTTP path remains sync sequential (KOJAK-74 will apply parallel `sendAsync`). Numbers below
show per-message cost at different webhook latencies — useful for understanding the gap that
KOJAK-74 closes:

| batchSize | latency 0 ms     | latency 20 ms     | latency 100 ms    |
|-----------|------------------|-------------------|-------------------|
| 10        | 0.638 ms/op      | 26.429 ms/op      | 108.515 ms/op     |
| 50        | 0.321 ms/op      | 24.892 ms/op      | 105.313 ms/op     |
| 100       | 0.290 ms/op      | 26.545 ms/op      | 107.714 ms/op     |

Flat per-message latency at `latencyMs=20/100` confirms HTTP is fully sequential: each request
waits for the previous response before the next goes out.

## Verification context

- Unit tests: `KafkaMessageDelivererBatchTest` covers empty input, all-success ordering,
  single flush call (verified via flush counter), synchronous send exception (Permanent +
  Retriable variants), and future-based async exception.
- Integration tests in `okapi-integration-tests` continue to pass with real Postgres + Kafka.
- ktlint clean, configuration cache reuses across modules.

## What's next

1. **HTTP `deliverBatch`** (KOJAK-74) — analogous fire-all-await for HTTP via parallel
   `httpClient.sendAsync`. Expected impact at realistic webhook latency
   (`httpLatencyMs ∈ {20, 100}`): from ~38 / ~9 msg/s baseline to **~500-2,000 msg/s** range,
   depending on host/connection pool reuse.
2. **Batch UPDATE via JDBC `executeBatch`** (KOJAK-75). Now load-bearing: at `batchSize=100`
   the N individual UPDATE statements have become the dominant per-batch cost. Expected
   to shift `batchSize=100` Kafka throughput from ~5,200 toward the ~10,000 msg/s range.
3. **Concurrent processor fan-out** (KOJAK-77) — multi-threaded scheduler. Multiplies all
   of the above by N workers.
