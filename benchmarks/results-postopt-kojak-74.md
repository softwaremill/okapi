# HTTP deliverBatch parallel sendAsync — Results (KOJAK-74)

Measured on MacBook M3 Max, JDK 21 LTS, Postgres 16 + WireMock (in-JVM) via Testcontainers,
full JMH config: `fork=2, warmup=3 × 10s, iter=5 × 30s` — n=10 samples per benchmark.

## Headline numbers — HTTP throughput

Baseline is sequential blocking `httpClient.send()` (from
[`results-kafka-deliverbatch.md`](results-kafka-deliverbatch.md#http-throughput-companion-benchmark)).
Optimized is parallel `httpClient.sendAsync()` fire-all + `thenApply`/`exceptionally` + `join`.

| batchSize | Baseline (ms/op) | Optimized (ms/op) | **Improvement** |
|-----------|-------------------|---------------------|------------------|
| **latency 0 ms**  |                 |                     |                  |
| 10        | 0.638             | 1.333 ± 0.066       | **0.48×** (slower) |
| 50        | 0.321             | 0.404 ± 0.032       | **0.79×** (slower) |
| 100       | 0.290             | 0.320 ± 0.036       | **0.91×** (slower) |
| **latency 20 ms** |                 |                     |                  |
| 10        | 26.429            | 5.041 ± 0.373       | **5.24×**        |
| 50        | 24.892            | 2.464 ± 0.222       | **10.10×**       |
| 100       | 26.545            | 2.175 ± 0.032       | **12.21×**       |
| **latency 100 ms**|                 |                     |                  |
| 10        | 108.515           | 14.119 ± 2.278      | **7.69×**        |
| 50        | 105.313           | 7.484 ± 0.130       | **14.07×**       |
| 100       | 107.714           | 7.059 ± 0.045       | **15.26×**       |

Translated to msg/s (`@OperationsPerInvocation(1000)`):

| batchSize | latency 0 ms | latency 20 ms | latency 100 ms |
|-----------|--------------|----------------|-----------------|
| 10        | ~750 msg/s   | ~198 msg/s     | ~71 msg/s       |
| 50        | ~2,475 msg/s | ~406 msg/s     | ~134 msg/s      |
| 100       | ~3,125 msg/s | ~460 msg/s     | ~142 msg/s      |

Baseline msg/s for comparison: ~1,567–3,448 (latency 0), ~38–40 (latency 20), ~9.2–9.5 (latency 100),
flat across `batchSize` — confirming baseline was fully sequential.

Raw JSON: [`http-deliverbatch.json`](http-deliverbatch.json).

## What changed

`HttpMessageDeliverer.deliverBatch` now fires all requests before awaiting any of them:
1. **Fire** — call `httpClient.sendAsync()` for every entry (non-blocking; a synchronous failure
   building one entry's request, e.g. corrupt `deliveryMetadata`, is isolated to that entry via a
   `SendAttempt` sealed type and does not prevent the rest of the batch from firing — mirrors the
   `SendOutcome` pattern from `KafkaMessageDeliverer`).
2. **Classify inline** — each future is chained with `.thenApply { classifyResponse(...) }.exceptionally { classifyThrowable(...) }`
   so every future always completes successfully with a `DeliveryResult`, never exceptionally.
3. **Await** — `.join()` per entry, in input order; since step 2 guarantees no exceptional
   completion, `join()` never throws. The JDK `HttpClient`'s own per-request 30s timeout
   (`HttpRequest.timeout()`) bounds the wait — no separate await-timeout needed (unlike Kafka's
   `flush()`, which has no equivalent per-record deadline).

`deliver()` was refactored to share `buildRequest`/`classifyResponse`/`classifyThrowable` helpers
with `deliverBatch`, with no single-entry behavior change (existing `HttpMessageDelivererTest`
passes unmodified). `classifyThrowable` also gained an explicit `SSLException` → `PermanentFailure`
branch (checked before `IOException`, since `SSLException` is a subtype) — a TLS handshake/cert
failure is a configuration problem that won't fix itself on retry, unlike a transient connection
reset.

## Reading the table

- **Real gains at realistic latency, but short of the ticket's optimistic 1000+ msg/s target.**
  At `batchSize=50, latencyMs=20` (the ticket's headline scenario) we measured **~406 msg/s**
  (10.1×), not 1000+. The parallelization itself is proven working — see the concurrency section
  below — but per-batch fixed costs (DB claim query, `batchSize` individual `UPDATE` statements,
  transaction commit, JVM/connection-pool bookkeeping) that were previously hidden behind
  sequential network wait now make up a larger share of wall time once the network wait is
  parallelized away. This is the same effect the Kafka results doc flagged as "next bottleneck":
  KOJAK-75 (batch `UPDATE` via JDBC `executeBatch`) directly attacks this, and should lift these
  numbers further without any change to `HttpMessageDeliverer` itself.
- **`latencyMs=0` regressed slightly (0.79×–0.91×), `batchSize=10` more noticeably (0.48×).**
  Expected: with no network wait to hide, `sendAsync`'s extra `CompletableFuture` allocation and
  chaining (`thenApply`/`exceptionally`) is pure overhead versus a single blocking `send()` call.
  At `batchSize=10` this fixed per-request overhead is a larger fraction of an already-tiny
  10-message batch. This confirms the AC's own framing — `latencyMs=0` is "already CPU-bound;
  library + DB + WireMock overhead" — the optimization targets I/O-bound webhook delivery, not
  the zero-latency floor.
- **Improvement grows with both `batchSize` and `latencyMs`** (5.2× → 15.3× from
  `(10, 20ms)` to `(100, 100ms)`), consistent with the mechanism: more concurrent in-flight
  requests and a larger per-request wait to overlap both increase the ratio of hidden-vs-exposed
  latency.
- **Baseline is flat across `batchSize`** (as previously documented) — confirms the "before"
  picture really was fully sequential, so the comparison is apples-to-apples.

## Concurrency proof

`HttpMessageDelivererBatchConcurrencyTest` fires a 10-entry batch against a WireMock stub with a
fixed 300 ms per-request delay and inspects WireMock's request journal (`allServeEvents`): all 10
requests' `loggedDate` timestamps fall within a single 300 ms window of each other (spread `<
delayMs`), and the overall `deliverBatch` call completes in well under half of the 3000 ms a fully
sequential implementation would need. This is direct evidence — not just an aggregate timing
inference — that the requests were in flight concurrently.

## Verification context

- Unit tests: `HttpMessageDelivererBatchTest` covers empty input, all-success ordering, mixed
  success/failure classification, all-fail batch, connection reset, poison-pill metadata isolation,
  a custom injected `HttpClient`, and a TLS handshake failure classifying as `PermanentFailure`.
- `HttpMessageDelivererBatchConcurrencyTest` proves concurrent in-flight requests via WireMock's
  request log (see above).
- Existing single-entry `HttpMessageDelivererTest` passes unmodified after the `deliver()` refactor.
- Integration tests (`HttpEndToEndTest`, `MysqlHttpEndToEndTest` in `okapi-integration-tests`)
  continue to pass with real Postgres + WireMock, now exercising `deliverBatch` end-to-end via
  `OutboxEntryProcessor`.
- ktlint clean project-wide (`ktlintCheck`); full `./gradlew build` green.

## What's next

1. **Batch UPDATE via JDBC `executeBatch`** (KOJAK-75) — now the more clearly load-bearing
   bottleneck for HTTP too, for the same reason it already was for Kafka: with network wait
   parallelized away, the N individual per-entry `UPDATE` statements are next in line.
2. **Multi-threaded scheduler with pluggable executor** (KOJAK-77) — multiplies both the Kafka
   and HTTP `deliverBatch` gains across N concurrent workers.
