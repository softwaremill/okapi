# KOJAK-76 measurement scripts — Postgres partial index for PENDING entries

Plain `psql` scripts, not JMH: the question was "does the planner behave differently, and by how
many buffers", which `EXPLAIN (ANALYZE, BUFFERS)` answers directly and a throughput benchmark would
only obscure. Findings: [`../results-postopt-KOJAK-76.md`](../results-postopt-KOJAK-76.md).

They are kept so the conclusion can be re-checked rather than taken on trust — particularly at a
different scale, on different hardware, or on a future Postgres major where index-maintenance
behaviour may differ.

## Running them

```sh
docker run -d --name kojak76 -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=okapi postgres:16
until docker exec kojak76 pg_isready -U postgres; do sleep 1; done

docker exec -i kojak76 psql -U postgres -d okapi -q < 01-setup.sql                    # ~8s
docker exec -i kojak76 psql -U postgres -d okapi -q < 02-measure-baseline.sql
docker exec -i kojak76 psql -U postgres -d okapi -q < 03-create-index-and-measure.sql
docker exec -i kojak76 psql -U postgres -d okapi -q < 04-measure-under-churn.sql

docker rm -f kojak76
```

Run them in order — each builds on the previous one's state. Total wall time well under a minute.

## What each one does

| Script | Purpose |
|---|---|
| `01-setup.sql` | Schema copied verbatim from `001__create_okapi_outbox_table.sql`, then 1,000,000 DELIVERED + 1,000 PENDING rows, `VACUUM ANALYZE` |
| `02-measure-baseline.sql` | `claimPending`'s exact SQL under four planning modes: SQL literal, bound parameter with a custom plan, bound parameter with a **forced generic plan**, and the default `auto` mode past the prepare threshold |
| `03-create-index-and-measure.sql` | Times `CREATE INDEX CONCURRENTLY`, reports index sizes, repeats the measurements |
| `04-measure-under-churn.sql` | 30,000 PENDING→DELIVERED transitions with autovacuum deliberately below its threshold, then compares with and without the partial index on the same churned state |

## Two things worth preserving about the method

**Measure through a bound parameter, not a literal.** `PostgresOutboxStore.claimPending` issues
`WHERE status = ?`. A partial index is only usable when the planner can prove the query predicate
implies the index predicate, which holds for a custom plan but not a generic one. An `EXPLAIN` typed
into psql with `status = 'PENDING'` can therefore show the index being used while the application
never touches it. `02` covers both, plus `force_generic_plan`, so the difference is visible rather
than assumed.

**Keep autovacuum below its threshold in the churn test.** On a 1M-row table autovacuum fires at
roughly `50 + 0.2 × 1M` ≈ 200k changes, so 30k transitions leave dead index entries in place. That
is the realistic steady state for an outbox, and it is the state in which a partial index would be
expected to win if it ever does.
