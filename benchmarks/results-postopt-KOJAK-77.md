# Multi-threaded scheduler with pluggable executor — Results (KOJAK-77)

Measured on the same machine/config as [`results-kafka-deliverbatch.md`](results-kafka-deliverbatch.md):
Postgres 16 + Kafka 3.8.1 via Testcontainers, full JMH config `fork=2, warmup=3 × 10s, iter=5 × 30s`
(n=10 samples per benchmark). **JDK 25.0.2** this time (not the 21 LTS used for earlier docs in
this directory) — notable because it means [JEP 491](https://openjdk.org/jeps/491) (removes
`synchronized`-block carrier-thread pinning for virtual threads) is present; the "virtual threads
lose to pinning on older JDKs" excuse does not apply to this run.

`OutboxSchedulerConcurrencyBenchmark` drains a fixed backlog of 6,400 pending entries through
`concurrency` parallel `OutboxProcessor.processNext(batchSize=100)` calls per round — one call per
worker, each on its own transaction/connection, exactly mirroring `OutboxScheduler`'s internal
fan-out (the scheduler's polling loop itself is bypassed, same rationale as the other throughput
benchmarks in this directory). Kafka `deliverBatch` is the transport, since HTTP `deliverBatch`
(KOJAK-74) isn't implemented yet and this benchmark is meant to isolate fan-out scaling, not
transport I/O.

## Headline numbers

| concurrency | platform (ms/op) | virtual (ms/op) | platform msg/s | virtual msg/s | virtual vs platform |
|---|---|---|---|---|---|
| 1  | 0.357 ± 0.097 | 0.347 ± 0.070 | ~2,801 | ~2,882 | +3% (noise — CIs overlap heavily) |
| 4  | 0.099 ± 0.003 | 0.104 ± 0.003 | ~10,101 | ~9,615 | **−5%** |
| 16 | 0.058 ± 0.005 | 0.058 ± 0.002 | ~17,241 | ~17,241 | 0% (tied) |
| 64 | 0.054 ± 0.002 | 0.058 ± 0.003 | ~18,519 | ~17,241 | **−7%** |

Raw JSON: [`scheduler-concurrency.json`](scheduler-concurrency.json).

Speedup vs. `concurrency=1, platform` baseline:

| concurrency | platform speedup | virtual speedup |
|---|---|---|
| 4  | **3.6×** | 3.4× |
| 16 | **6.2×** | 6.2× |
| 64 | **6.6×** | 6.2× |

## The ticket's hypothesis did not hold — reporting it straight

The ticket's baseline expectations were:
- `concurrency=4, platform`: target ~3-4× single-threaded — **confirmed** (3.6×).
- `concurrency=16, virtual` should outperform `concurrency=16, platform` by 15-25% — **not
  observed**. They're tied (0% difference, well within error bars).
- `concurrency=64+, virtual` should be "the only viable option" — **not observed**. Platform is
  actually ~7% *faster* than virtual at concurrency=64.

At no tested concurrency level does virtual meaningfully beat platform; platform is same-or-ahead
throughout. Since this run already has JEP 491 (JDK 25), the usual "virtual threads regress
because of pre-JDK-24 pinning" explanation doesn't apply here. The more likely explanation:
virtual threads' advantage shows up when task count vastly exceeds available platform threads
(hundreds to thousands of concurrent blocking tasks contending for a small carrier pool) — this
benchmark only ever runs *exactly* `concurrency` tasks against a platform pool sized to exactly
`concurrency`, so there's no oversubscription for virtual threads to fix, and they pay their own
(small) continuation-management overhead for no return. The ticket's "64+" ceiling likely needs to
be an order of magnitude higher (hundreds to low thousands) before virtual threads' benefit would
show up — genuinely testing that means decoupling worker count from DB connection limits, which
is out of scope here.

**Practical takeaway: default to platform threads (`OutboxSchedulerConfig`'s default) at every
concurrency level tested (1-64).** `workerExecutorFactory` remains available for users who want to
verify virtual threads at concurrency well beyond 64 for their own workload, but nothing in this
data recommends switching the default.

## Where the scaling actually plateaus

Sublinear scaling from `concurrency=4` (3.6×) to `concurrency=16` (6.2×) to `concurrency=64`
(6.6×) — far from the linear 4×/16×/64× the "multiplies throughput linearly" framing in the ticket
might suggest. Two contributing factors:
- **Shared, single-instance backend.** One Postgres container and one Kafka broker mean workers
  increasingly contend for the same connection acceptance path and broker I/O as concurrency
  rises — this benchmark measures the fan-out mechanism's overhead, not unlimited horizontal DB
  scaling, and the "concurrency × instances ≤ `max_connections` / 2" rule of thumb below exists
  precisely because of this.
- **Round-count artifact at high concurrency.** With `batchSize=100` fixed, `concurrency=64` claims
  all 6,400 entries in a *single* round — so its measurement is dominated by one-time per-round
  costs (opening `concurrency` fresh connections, `claimPending` query planning) with no further
  rounds to amortize them over. Lower concurrency values run many rounds, averaging out that fixed
  cost. This means the `concurrency=64` number is a slight pessimistic outlier relative to a
  steady-state, many-round production workload — a caveat worth keeping in mind rather than reading
  64 as a hard ceiling.

## Tuning recommendation

- **`concurrency=4` to `concurrency=16`** is the practical sweet spot for a single Postgres+Kafka
  backend on this hardware: most of the available speedup (3.6×-6.2×) is already captured, and
  gains beyond 16 are marginal (6.2× → 6.6×) while consuming more DB connections.
- Keep the default `defaultPlatformPool` executor. Nothing in this data supports switching to
  `virtualThreadPool` at the concurrency levels tested.
- **Rule of thumb documented on `OutboxSchedulerConfig`:** `concurrency × instances ≤
  max_connections / 2` — cross-instance coordination is free (`FOR UPDATE SKIP LOCKED` handles
  disjoint claims across both workers and instances with no app-level coordination), but every
  worker across every instance holds a DB connection for the duration of its batch.

## Verification context

- Unit tests: `OutboxSchedulerConfigTest` (concurrency validation 1-256, `defaultPlatformPool`
  thread naming, `virtualThreadPool` runs on virtual threads), `OutboxSchedulerTest` (fan-out
  dispatches exactly `concurrency` workers per tick, `concurrency=1` never touches
  `workerExecutorFactory`, one worker's exception doesn't block the others).
- Integration test: `ConcurrentClaimTests` extended with a real `OutboxScheduler`
  (`concurrency=4`) against both Postgres and MySQL — disjoint claims across workers *and* ticks,
  zero delivery amplification.
- `okapi-spring-boot`: `okapi.processor.concurrency` property wired through, with startup-failure
  coverage for invalid values.
- ktlint clean, full `./gradlew build` green (core, spring-boot, integration-tests).
