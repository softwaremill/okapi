# Route-aware claiming on a large outbox

Local exploratory measurements with PostgreSQL 16 and MySQL 8.0 in Docker. The setup SQL files create a table with the Okapi columns and 1,000,000 `PENDING` rows. The oldest 900,000 use `delivery_type='unhandled'`; the newer 100,000 are split evenly across 100 `handled_00` through `handled_99` routes. Both the old `(status, created_at)` index and the new `(status, delivery_type, created_at, id)` index are present.

Run `okapi-116-pg-setup.sql` with `psql` or `okapi-116-my-setup.sql` with `mysql`, then run the matching `okapi-116-*-queries.sql` file. The query files compare one route, an `IN` query for 100 present routes, and an `IN` query for 100 absent routes. All use `FOR UPDATE SKIP LOCKED` and a batch limit of 100. The `okapi-116-*-client-100.sql` files send 100 separate empty claims through a SQL client.

| Case | PostgreSQL observed execution | MySQL observed execution |
| --- | ---: | ---: |
| One present route, first 100 rows | 0.12 ms | 0.49 ms |
| `IN` with 100 absent routes, ordered by type and creation time | 165 ms | 0.21 ms |
| 100 separate empty route claims, server-side loop | 1.57 ms | 2.04–2.37 ms |
| 100 separate empty route claims through one local SQL client process | 24 ms | 15 ms |

The PostgreSQL plan for the absent `IN` case scanned all 1,000,000 rows and removed them by filter, despite the new index. The one-route plan on both databases used the new composite index and stopped at the limit. This is why the processor claims one route at a time and stops once its batch is full. At 100 empty routes it still performs 100 small queries per poll; this cost grows with the number of configured routes.

The `okapi-116-*-write.sql` files insert 20,000 rows and update about 20,000 statuses inside rolled-back transactions. One run with the new index and one after dropping only that index gave:

| Operation | PostgreSQL with / without new index | MySQL with / without new index |
| --- | ---: | ---: |
| Insert 20,000 | 125 / 81 ms | 177 / 141 ms |
| Update about 20,000 statuses | 158 / 135 ms | 363 / 304 ms |

These write figures are **single local runs**, sensitive to cache state and test order. They show a real write-cost tradeoff, not a stable throughput estimate. The read measurements are also exploratory; the query plan and scanned-row counts are the main evidence. Integration tests separately cover route isolation, concurrent processors, migrations, and delivery behavior on both databases.
