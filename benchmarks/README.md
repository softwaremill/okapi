# Okapi Performance Benchmarks

This directory contains benchmark methodology, run instructions, and historical results.

## Running benchmarks

All benchmarks live in the `okapi-benchmarks` module and use [JMH](https://openjdk.org/projects/code-tools/jmh/)
via the [me.champeau.jmh](https://github.com/melix/jmh-gradle-plugin) Gradle plugin.

### Full baseline (production-quality numbers)

Default JMH config in `okapi-benchmarks/build.gradle.kts` uses:
- `fork = 2` — isolated JVMs to neutralize JIT-profile variance
- `warmupIterations = 3`, `warmup = 10s` — let JIT C2 settle
- `iterations = 5`, `timeOnIteration = 30s` — statistically meaningful sample
- `-Xms8g -Xmx8g -XX:+UseG1GC` — pinned memory and GC for reproducibility

```sh
./gradlew :okapi-benchmarks:jmh
```

Wall time ~30 min: Testcontainers spin-up plus the full JMH matrix — Kafka (3 batchSize combos) and HTTP
(3 batchSize × 3 httpLatencyMs = 9 combos) throughput benchmarks plus DelivererMicroBenchmark, each with
fork=2, 3 warmup + 5 measurement iterations.

Result JSON: `okapi-benchmarks/build/reports/jmh/results.json`

### Quick smoke run

For development iteration when you don't need statistically significant numbers:

```sh
./gradlew :okapi-benchmarks:jmhJar
java -jar okapi-benchmarks/build/libs/okapi-benchmarks-jmh.jar \
  "ThroughputBenchmark" -f 1 -wi 1 -i 2 -w 10s -r 15s
```

Wall time: ~5-8 minutes.

### Single benchmark

```sh
java -jar okapi-benchmarks/build/libs/okapi-benchmarks-jmh.jar \
  "KafkaThroughputBenchmark" -p batchSize=50 -f 1 -wi 1 -i 2
```

## What we measure

### Throughput benchmarks (`*ThroughputBenchmark`)

End-to-end pipeline: insert N PENDING entries, then call `OutboxProcessor.processNext()`
in a tight loop until drained. The `OutboxScheduler` is bypassed deliberately — we measure
**processing capacity**, not polling cadence (which is a deployment-time knob).

- `KafkaThroughputBenchmark` — real Postgres + real Kafka via Testcontainers
- `HttpThroughputBenchmark` — real Postgres + WireMock HTTP target with `@Param httpLatencyMs`
  injecting `0`/`20`/`100` ms server-side delay (library-only ceiling vs realistic webhook)

Reported as `ops/s` where one op = one delivered message (via `@OperationsPerInvocation`).

### Microbenchmarks (`DelivererMicroBenchmark`)

Single-entry `deliver()` calls with mocked I/O:
- Kafka: `MockProducer` with auto-complete (no broker)
- HTTP: WireMock on loopback

Measures pure code overhead (JSON deserialization, record/request construction,
exception classification). Useful as "did optimization X regress the hot path?" baseline.

### Scheduler fan-out (`OutboxSchedulerConcurrencyBenchmark`)

Drains a fixed backlog through `concurrency` parallel `processNext()` calls per round — one call
per worker, each on its own transaction, mirroring `OutboxScheduler`'s internal fan-out (the
scheduler's polling loop itself is still bypassed). `@Param executorType ∈ {platform, virtual}` ×
`@Param concurrency ∈ {1, 4, 16, 64}`. Transport is Kafka `deliverBatch` — real Postgres + real
Kafka via Testcontainers. See [`results-postopt-KOJAK-77.md`](results-postopt-KOJAK-77.md).

## How to read results

Throughput benchmarks report **ops/s = msg/s** thanks to `@OperationsPerInvocation`.

```
Benchmark                                  (batchSize)   Mode  Cnt   Score   Error  Units
KafkaThroughputBenchmark.drainAll                   10  thrpt    5  450.2 ± 18.3  ops/s
KafkaThroughputBenchmark.drainAll                   50  thrpt    5  890.5 ± 22.1  ops/s
KafkaThroughputBenchmark.drainAll                  100  thrpt    5  920.7 ± 31.4  ops/s
```

The `Score` is the headline number. The `Error` is a 99.9% confidence interval — a tight error
(< 5% of score) means the result is trustworthy; a wide error means run more iterations or
investigate variability sources (background processes, thermal throttling, GC).

## Caveats — important for honest reporting

- **Localhost Testcontainers ≠ production.** Kafka container on the same host has ~0.5ms RTT;
  a real cluster typically has 5-50ms. Real-world throughput will be **2-10× lower** than these
  benchmarks suggest. Treat numbers as **upper bounds** for the library's processing capacity.
- **HTTP benchmark uses WireMock in-JVM**, which adds ~0.3 ms overhead per request (Jetty
  servlet pipeline). At `httpLatencyMs=0` the measurement reflects "library + DB + WireMock
  overhead", not pure library throughput. For tighter measurement consider replacing WireMock
  with `MockWebServer` (Square) — see [`results-baseline-2026-04.md`](results-baseline-2026-04.md)
  notes on benchmark methodology.
- **`httpLatencyMs` is server-side delay**, not network RTT. Real production webhook latency
  is dominated by the target service's processing time + network — pick the value closest
  to your target. With `httpLatencyMs=100`, sequential delivery is bounded at
  `1000ms / 100ms = 10 msg/s/thread` regardless of library efficiency.
- **Multi-threaded scheduler is single-instance, single-backend.** `OutboxSchedulerConcurrencyBenchmark`
  fans out against one Postgres container and one Kafka broker; it measures the fan-out
  mechanism's overhead, not unlimited horizontal DB/broker scaling. See
  [`results-postopt-KOJAK-77.md`](results-postopt-KOJAK-77.md) for the concurrency=64 round-count
  artifact (a single round drains the whole fixed backlog, so per-round fixed costs aren't
  amortized the way they are at lower concurrency).

## Historical baselines

- [`results-baseline-2026-04.md`](results-baseline-2026-04.md) — pre-optimization baseline
  (sync sequential delivery, single-threaded scheduler)
- [`results-postopt-KOJAK-75.md`](results-postopt-KOJAK-75.md) — batch UPDATE via JDBC `executeBatch`
- [`results-postopt-KOJAK-77.md`](results-postopt-KOJAK-77.md) — multi-threaded scheduler concurrency knob
- [`results-postopt-KOJAK-75.md`](results-postopt-KOJAK-75.md) — batch UPDATE via JDBC
  `executeBatch()`, Postgres: 10.2×
- [`results-mysql-rewrite-batched-statements.md`](results-mysql-rewrite-batched-statements.md) —
  same optimization on MySQL: mechanism verified correct, but no net speedup measured at this
  batch size/hardware — read before assuming the Postgres multiplier transfers
