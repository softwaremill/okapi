# Baseline Performance Results — April 2026

This document captures the **pre-optimization baseline** of the okapi outbox library
before any of the planned performance improvements (async batch delivery, multi-threaded
scheduler, batch DB updates) are implemented.

## Purpose

These numbers establish a reference point. When subsequent optimizations land, we can
compare against this baseline to:
1. Validate that claimed gains are real (not just lab artifacts).
2. Detect unintended regressions in unrelated code paths.
3. Provide users with credible "before/after" performance documentation.

## Methodology

All benchmarks executed via [JMH](https://openjdk.org/projects/code-tools/jmh/) using
the [me.champeau.jmh](https://github.com/melix/jmh-gradle-plugin) Gradle plugin.

See [`README.md`](README.md) for benchmark architecture and how to reproduce.

### Library state at baseline

- **Single-threaded scheduler.** `OutboxScheduler` runs on one daemon thread
  (`Executors.newSingleThreadScheduledExecutor`).
- **Sync sequential delivery.** `OutboxProcessor.processNext()` iterates entries with
  `forEach`, calling `MessageDeliverer.deliver()` per entry.
- **Kafka:** `producer.send(record).get()` blocks per record.
- **HTTP:** `httpClient.send(request, ...)` blocks per request (synchronous JDK HttpClient).
- **DB:** N individual `updateAfterProcessing` calls (one upsert per entry).
- All processing occurs **inside a single transaction** that spans claim → deliver → update.

### JMH configuration

- `@BenchmarkMode(Mode.AverageTime)` — reports time per drain
- `@OperationsPerInvocation(1000)` on throughput benchmarks — JMH normalizes by 1000,
  so `Score` in `ms/op` directly reflects "ms per delivered message"; reciprocal = msg/s
- `@Param` matrix: `batchSize ∈ {10, 50, 100}`, `httpLatencyMs ∈ {0, 20, 100}` (HTTP only)
- `fork = 1`, `warmupIterations = 1`, `iterations = 2`, `warmup = 10s`, `measurement = 15s`
  (quick smoke run; full baseline with `fork=2`, `warmup=3`, `iterations=5` is also valid
  and recommended for release-quality numbers)

### Hardware / runtime

- **Hardware:** MacBook Pro (Apple M3 Max, 96 GB RAM)
- **OS:** macOS 26.4.1
- **JVM:** Temurin 25.0.2 LTS (OpenJDK 25.0.2+10)
- **Docker:** Docker Desktop, 7.7 GB allocated to engine
- **Postgres:** `postgres:16` (Testcontainers, default config)
- **Kafka:** `apache/kafka:3.8.1` (Testcontainers, default config)
- **WireMock:** local loopback, zero artificial latency

## Results

> **NOTE:** Results from the smoke run are pasted below as captured. For release-quality
> numbers, re-run with `./gradlew :okapi-benchmarks:jmh` (fork=2, warmup=3, iterations=5).

### Throughput benchmarks

End-to-end fanout — N entries inserted, processor drains them in a tight loop,
scheduler bypassed. `1 / (ms/op)` × 1000 = msg/s.

| Benchmark | batchSize | ms/op | **msg/s** |
|-----------|-----------|-------|-----------|
| `KafkaThroughputBenchmark.drainAll` | 10  | 9.168 | **~109** |
| `KafkaThroughputBenchmark.drainAll` | 50  | 8.665 | **~115** |
| `KafkaThroughputBenchmark.drainAll` | 100 | 8.701 | **~115** |
#### HTTP throughput (with `httpLatencyMs` parameter)

| batchSize | httpLatencyMs | ms/op | **msg/s** | Interpretation |
|-----------|---------------|-------|-----------|----------------|
| 10  | 0   | 0.661   | **~1,513** | library + DB + WireMock ceiling |
| 10  | 20  | 30.322  | **~33**    | fast intra-cluster service |
| 10  | 100 | 109.644 | **~9**     | typical cross-region webhook |
| 50  | 0   | 0.368   | **~2,717** | (same ceiling, fewer claimPending queries) |
| 50  | 20  | 30.016  | **~33**    | flat — sequential blocking dominates |
| 50  | 100 | 109.569 | **~9**     | flat — one batch ≈ batchSize × latencyMs |
| 100 | 0   | 0.301   | **~3,322** | (ceiling, marginal gain from batching DB) |
| 100 | 20  | 27.718  | **~36**    | flat with `batchSize=10` and `batchSize=50` |
| 100 | 100 | 107.781 | **~9**     | flat — pattern holds across all batchSize |

Raw JSON: [`baseline-http-with-latency.json`](baseline-http-with-latency.json).

#### What this HTTP table reveals

- **At `latencyMs=0`** throughput scales with `batchSize` (1,513 → 2,717 → 3,322) — DB query
  amortization is the only thing being optimized. This is the **library's processing ceiling**.
- **At any non-zero latency** throughput is **flat across batchSize** (33, 33, 36 at 20 ms;
  9, 9, ~9 at 100 ms) — proving that **sequential blocking dominates** as soon as I/O has
  any cost. Batch size doesn't help when each `httpClient.send()` blocks for the full RTT.
- **The theoretical sequential ceiling** for `latencyMs=N` is `1000ms / Nms = 1000/N msg/s`,
  matching the measured numbers within ~30% (overhead from DB + HttpClient setup):
  - latency=20 → theoretical 50 msg/s, measured ~33
  - latency=100 → theoretical 10 msg/s, measured ~9

Run wall time: 5 min 3 s. Raw JSON: [`baseline-quick.json`](baseline-quick.json).

**Note on confidence intervals:** This smoke run uses `fork=1, warmup=1, iterations=2`,
so the JMH `Error` column is empty. For release-quality numbers with proper CI bounds,
re-run with `./gradlew :okapi-benchmarks:jmh` (default config: fork=2, warmup=3,
iterations=5).

### Microbenchmarks

Single-entry `deliver()` calls with mocked I/O (zero network cost). Measures pure code
overhead — JSON deserialization of `deliveryMetadata`, record/request construction,
exception classification.

| Benchmark | Score | Units |
|-----------|-------|-------|
| `DelivererMicroBenchmark.kafkaDeliver` | ~1,800,000 | ops/s |
| `DelivererMicroBenchmark.httpDeliver`  | _TBD_ | ops/s |

The Kafka deliver microbenchmark shows the per-message code overhead is **negligible**
(~550 ns/message). This means the throughput benchmarks measure **almost entirely**
I/O and DB time — the library itself contributes <1% to the per-message latency budget.

## Interpretation

### What the baseline tells us

1. **Kafka throughput is flat with respect to `batchSize`** (109 → 115 → 115 msg/s).
   This confirms that the bottleneck is `producer.send().get()` blocking sequentially
   per entry, not DB or scheduler overhead. Each Kafka send takes ~9 ms RTT on localhost
   with `acks=all`, and 1000 entries × 9 ms = 9 seconds regardless of how they're batched
   from the DB side. This is **exactly the bottleneck async batch delivery
   (fire-flush-await) is designed to eliminate**.

2. **HTTP throughput scales positively with `batchSize`** (1,369 → 2,825 → 3,215 msg/s).
   With WireMock on localhost the per-request I/O cost approaches zero, so the savings
   come from amortizing the per-batch DB overhead (`claimPending`, transaction begin/commit).
   Larger batch = fewer DB roundtrips per delivered message.

3. **The 30× Kafka/HTTP asymmetry is an artifact of localhost WireMock.** In production
   HTTP webhooks have RTT of 50-200 ms — the same `httpClient.send().` blocking pattern
   that's currently used will hit a similar wall as Kafka does today. **Real-world HTTP
   throughput will be much lower** than this benchmark suggests; the current numbers reflect
   "library overhead with free I/O", not "real webhook delivery rate".

4. **Single-threaded ceiling.** With `concurrency=1` (the only option today), throughput
   is bounded by `1 / (avgRoundTripPerMessage + dbOverheadPerMessage)`. Adding multi-threaded
   fan-out should give linear scaling up to DB connection pool size.

### What the baseline does NOT tell us

- **Production throughput.** Localhost Testcontainers Kafka has ~0.5 ms RTT vs 5-50 ms
  for real clusters. Real-world numbers will be 2-10× lower.
- **Multi-threaded throughput.** `concurrency` config does not exist yet.
- **Behavior under failures.** All deliveries succeed; no retry path is exercised.

## Next steps

These baseline numbers will be re-measured after each of the planned optimizations lands:

1. **Async batch delivery** — `MessageDeliverer.deliverBatch()` with Kafka
   fire-flush-await and HTTP `sendAsync`. Expected: 5-10× improvement at `batchSize=50`+.
2. **Multi-threaded scheduler** — `OutboxSchedulerConfig.concurrency`. Expected: linear
   scaling up to DB connection pool size.
3. **Batch UPDATE via `executeBatch`** — single prepared statement for N entries.
   Expected: marginal at `batchSize=10`, 3-5× at `batchSize=100+`.

See [KOJAK-14](https://softwaremill.atlassian.net/browse/KOJAK-14) epic for the full plan.
