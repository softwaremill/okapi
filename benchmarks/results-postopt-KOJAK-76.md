# Postgres partial index for PENDING entries — Results (KOJAK-76)

**Outcome: not implemented.** The proposed index was measured and rejected — it saves roughly one
buffer hit out of twenty-three, and that gap does not widen with table size.

Measured with plain `psql` rather than JMH: the question was whether the planner behaves differently
and by how many buffers, which `EXPLAIN (ANALYZE, BUFFERS)` answers directly, while a throughput
benchmark would bury a one-page difference under transport and transaction noise. Scripts to
reproduce: [`kojak-76/`](kojak-76/).

Postgres 16 (same image the Testcontainers suites use), schema copied verbatim from
`001__create_okapi_outbox_table.sql`, 1,000,000 DELIVERED + 1,000 PENDING rows, `VACUUM ANALYZE`,
warm cache, single connection. The query under test is `PostgresOutboxStore.claimPending`'s exact
SQL — `SELECT * FROM okapi_outbox WHERE status = ? ORDER BY created_at ASC LIMIT ? FOR UPDATE SKIP
LOCKED` — executed through a prepared statement with a **bound** `status` parameter, as the driver
does.

## The proposal

```sql
CREATE INDEX CONCURRENTLY idx_okapi_outbox_pending_created_at
    ON okapi_outbox (created_at) WHERE status = 'PENDING';
```

alongside the existing `idx_okapi_outbox_status_created_at (status, created_at)`.

## Headline numbers

| Scenario | Total buffers | Index pages | Exec time | Partial index size |
|---|---|---|---|---|
| Composite only, static table | 25 | 5 | 0.027–0.047 ms | — |
| + partial index, static table | 24 | 4 | 0.016–0.032 ms | 40 kB |
| Composite only, after 30k churn | 23 | 5 | 0.024 ms | — |
| + partial index, after 30k churn | 22 | 4 | 0.019 ms | 232 kB |

The saving is **one index page**. The remaining ~19 buffers are heap fetches and `LockRows` work,
identical either way. Execution times of 20–40 µs on a warm cache are noise at this resolution and
should not be read as a 40% improvement.

## Why the ticket's premise doesn't hold

The ticket reasons that the composite index "covers ALL rows" and is therefore expensive to search.
It isn't. A btree on `(status, created_at)` keeps all PENDING entries contiguous, and the planner
descends straight into that range — five page accesses on a 1M-row table, of which the partial index
saves one.

**And that ceiling is permanent.** Btree depth grows logarithmically, so the partial index saves
exactly the one level the `status` prefix contributes. At 100M rows the composite index gains about
one more level and so does the partial index; the difference stays ~1 page. There is no table size
at which this optimization becomes significant.

## The bloat scenario, tested explicitly

The strongest case for a partial index is dead-entry accumulation: rows churn PENDING→DELIVERED, and
between vacuums the PENDING key range of the composite index fills with dead tuples that the claim
scan must skip.

`04-measure-under-churn.sql` drives 30,000 transitions with autovacuum deliberately below its
threshold (~200k changes on a 1M-row table) — the realistic steady state for an outbox. Bloat never
materialised: Postgres 14+'s bottom-up index deletion keeps the PENDING range clean, and the
composite index actually got *cheaper* (25 → 23 buffers) as the churned heap pages warmed.

## Two planning assumptions that turned out wrong

Both were raised as risks before measuring, and both cut against the ticket rather than for it.

**Startup cost is not the problem.** `CREATE INDEX CONCURRENTLY` took **264 ms** on 1M rows —
seconds, not minutes, at 10M. The concern that a Liquibase migration running at application startup
would stall boot is largely unfounded at realistic outbox sizes. The reason to decline is the absent
benefit, not the build cost.

**The bind-parameter risk is real but does not bite.** With `plan_cache_mode = force_generic_plan`,
the planner falls back to the composite index — `status = $1` is unknown at plan time, so the partial
index is genuinely unusable, confirming the mechanism. Under the default `auto`, past the prepare
threshold, the cheaper custom plan wins and the partial index is chosen. No change to
`PostgresOutboxStore` would have been required. This is worth remembering for any *future* partial
index on this table: verify through a bound parameter, because a psql check with a literal proves
nothing about the application.

## Recommendation

Do not add the index. It would be a third index maintained on every `publish()` and every status
transition on a write-heavy table, bought for one buffer hit on the read path.

Revisit only with evidence from a real deployment — `pg_stat_statements` or buffer-read counters
showing `claimPending` actually reading from disk rather than hitting shared buffers. That would be
a different finding from this one and worth measuring again.

## Verification context

Everything measured here was a `shared hit`; nothing exercised a cold cache on a memory-starved
server, though only ~5 index pages are touched per claim and they stay resident by definition.
Single connection, so `FOR UPDATE SKIP LOCKED` contention between concurrent workers is untested —
a different axis, addressed by [`results-postopt-KOJAK-77.md`](results-postopt-KOJAK-77.md). Run on
Docker on a macOS VM, so absolute timings are not comparable with the JMH docs in this directory;
the buffer counts, which carry the conclusion, are hardware-independent.
