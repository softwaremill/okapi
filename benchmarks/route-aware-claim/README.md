# Route-aware claiming on a large outbox

Exploratory local measurements with PostgreSQL 16 and MySQL 8.0 in Docker. The setup SQL creates 1,000,000 `PENDING` rows: 900,000 older rows have an unsupported `delivery_type`, while 100,000 newer rows are spread across 100 supported types. The legacy `(status, created_at)` index stays in place for the public unrestricted claim API. The new PostgreSQL index is partial on `(delivery_type, created_at, id) WHERE status = 'PENDING'`; MySQL adds `(status, delivery_type, created_at, id)`.

Run the matching `*-setup.sql` and `*-queries.sql` files with `psql` or `mysql`. They compare one route with `IN` over 100 present or absent routes, all with `FOR UPDATE SKIP LOCKED` and limit 100. The `*-client-100.sql` files preserve an earlier alternative: 100 separate empty claims sent through one local SQL client process. The processor now uses **at most two claims per batch**: one for a rotating starting type, then one `IN` claim for all remaining types when the first claim did not fill the batch.

| Case | PostgreSQL observed execution | MySQL observed execution |
| --- | ---: | ---: |
| One present route, first 100 rows | ~0.15 ms | ~0.26–0.49 ms |
| `IN` with 100 absent routes, ordered by type and creation time | ~0.16 ms | ~0.21 ms |
| 100 separate empty route claims, server-side loop | 1.57 ms | 2.04–2.37 ms |
| 100 separate empty route claims through one local SQL client process | 24 ms | 15 ms |

Both current `IN` plans use the new route index, including PostgreSQL with a generic prepared plan. The PostgreSQL query must contain literal `status = 'PENDING'` so a generic plan can use its partial index. A preliminary full PostgreSQL index `(status, delivery_type, created_at, id)` produced a bad plan for the 100-absent-type query: it scanned 1,000,000 rows in ~165 ms. The partial index avoids that observed plan. The MySQL implementation also checks `BINARY delivery_type` to preserve exact type matching under case-insensitive database collations; the primary `IN` predicate still drives the index range scan.

The extra index costs writes. In five alternating series of 10,000 committed inserts and status updates against million-row tables, median times with / without the additional route index were:

| Operation | PostgreSQL partial index | MySQL full index |
| --- | ---: | ---: |
| Insert 10,000 | 57.46 / 39.03 ms (1.47×) | 94.95 / 80.63 ms (1.18×) |
| Update 10,000 statuses | 88.36 / 82.56 ms (1.07×) | 204.30 / 158.82 ms (1.29×) |

The `*-write.sql` files provide a small, rollback-based write probe; the table above is from repeated committed runs on comparable million-row tables, so its figures are not directly reproduced by those scripts. Results depend on cache, hardware, row distribution and workload. The plans and scanned-row counts matter more than these timings. Removing the legacy index made unrestricted `claimPending(limit)` very slow in this dataset, so both indexes remain. Integration tests separately cover migrations, route isolation, exact type matching, multiple routes and concurrent workers on both databases.
