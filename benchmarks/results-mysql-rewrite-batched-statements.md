# MySQL `rewriteBatchedStatements` — Results (KOJAK-75 follow-up)

Same machine/config as [`results-postopt-KOJAK-75.md`](results-postopt-KOJAK-75.md): JDK 21 LTS,
full JMH config `fork=2, warmup=3 × 10s, iter=5 × 30s` (n=10 samples per benchmark), MySQL 8.0 via
Testcontainers.

## Headline numbers — UPDATE phase only, 1000 entries

| Benchmark          | `rewriteBatchedStatements` | ms/op (per entry) | **Delta vs. false** |
|--------------------|:---:|---|---|
| `individualUpdates` | false | 0.383 ± 0.030 | — |
| `individualUpdates` | true  | 0.401 ± 0.048 | not significant |
| `batchUpdate`       | false | 0.356 ± 0.040 | — |
| `batchUpdate`       | true  | 0.372 ± 0.025 | not significant |

Raw JSON: [`mysql-outbox-store-update-batch.json`](mysql-outbox-store-update-batch.json).

**No configuration in this table shows a statistically significant difference from any other —
every score falls inside the others' error bars.** This does **not** mean `rewriteBatchedStatements`
is useless; it means this benchmark, on this hardware, can't demonstrate its benefit. Read on.

## What we verified independently of the numbers above

Because a null result here would otherwise look like "batching doesn't help on MySQL" — which
contradicts both the Connector/J docs and the Postgres result — we checked what actually crosses
the wire using Connector/J's own query profiler (`profileSQL=true&logger=StandardLogger`) on the
real `MysqlOutboxStore.updateAfterProcessingBatch` code path, batching 1000 `UPDATE`s:

- With `rewriteBatchedStatements=true`, the driver **does** rewrite the batch into a single
  multi-statement round trip. Server-side execution of that one combined statement: **2 ms**.
- Immediately after, decoding the combined response into 1000 individual JDBC update-counts
  (the `[FETCH]` phase in Connector/J's profiler log) took **126 ms**.

So the round-trip savings are real (2 ms of server work instead of ~1000 individual round trips),
but at this batch size they're offset by client-side cost of unpacking the combined response. The
two effects landing in the same ballpark is why `batchUpdate(true)` and `batchUpdate(false)` are
indistinguishable in the table above — not because the rewrite silently fails.

## Why this differs from the Postgres result

[`results-postopt-KOJAK-75.md`](results-postopt-KOJAK-75.md) measured a clean 10.2× for the
equivalent Postgres benchmark. That comparison isn't apples-to-apples with this one:

- PgJDBC pipelines batched statements over the extended query protocol with no configuration
  needed and no equivalent large-response decode step.
- This machine's round-trip latency to a loopback Testcontainers instance is already sub-millisecond
  for both databases — the scenario `rewriteBatchedStatements` targets (real network latency between
  app and DB) isn't present here for either engine, but MySQL's added decode cost only shows up
  because we went looking for it specifically.

## Practical takeaway

- The property does what Connector/J's docs say: it eliminates N−1 round trips. That's a genuine
  win when app↔DB round-trip time is non-trivial (production, cross-AZ, real network hops) —
  which this benchmark can't reproduce on localhost.
- At very large batch sizes, factor in the client-side decode cost this investigation surfaced;
  it isn't documented by MySQL and may partially offset the round-trip savings depending on your
  actual batch size and network latency. We don't have a documented threshold for where the
  trade-off flips.
- We recommend enabling `rewriteBatchedStatements=true` regardless (per the README) since it's
  never harmful for this UPDATE-by-primary-key pattern, but we can't publish a MySQL speedup
  multiplier the way we did for Postgres — measure it against your own production batch size and
  network latency if throughput at this stage of the pipeline matters to you.

## Verification context

- Diagnostic method: Connector/J `profileSQL=true&logger=com.mysql.cj.log.StandardLogger` against
  the real `MysqlOutboxStore.updateAfterProcessingBatch` path (not a synthetic query), 1000 entries.
- Benchmark: `MysqlOutboxStoreUpdateBatchBenchmark`, `@Param("false", "true")` on
  `rewriteBatchedStatements`, one physical connection reused for the whole JMH trial (not reopened
  per invocation) to match how okapi actually uses a connection per scheduler tick rather than per
  `processNext()` call.
