# Học: Kế toán kép (double-entry ledger)

## Statement paging — measured, not assumed

Seeded 20,000 `ledger_entry` rows for one account, ran `EXPLAIN ANALYZE` on the same query at
offset 0 and offset 19980 (`StatementQueryPerformanceIT`).

| Offset | Plan | actual time |
|---|---|---|
| 0 | `Index Only Scan using idx_entry_account_created` | 0.092 ms (execution time) |
| 19980 | `Index Only Scan using idx_entry_account_created` | 4.572 ms (execution time) |

Full plans from the real run:

```
=== EXPLAIN ANALYZE, offset 0 (first page) ===
Limit  (cost=0.41..1.70 rows=20 width=16) (actual time=0.051..0.057 rows=20 loops=1)
  ->  Index Only Scan using idx_entry_account_created on ledger_entry e  (cost=0.41..1287.21 rows=19979 width=16) (actual time=0.050..0.054 rows=20 loops=1)
        Index Cond: (account_id = '3'::bigint)
        Heap Fetches: 20
Planning Time: 0.082 ms
Execution Time: 0.092 ms

=== EXPLAIN ANALYZE, offset 19980 (last page of 20000) ===
Limit  (cost=1287.21..1287.28 rows=1 width=16) (actual time=4.513..4.518 rows=20 loops=1)
  ->  Index Only Scan using idx_entry_account_created on ledger_entry e  (cost=0.41..1287.21 rows=19979 width=16) (actual time=0.349..3.630 rows=20000 loops=1)
        Index Cond: (account_id = '3'::bigint)
        Heap Fetches: 3680
Planning Time: 0.635 ms
Execution Time: 4.572 ms
```

`idx_entry_account_created (account_id, created_at DESC, id DESC)` already covers the sort order,
so the query never needs a separate sort step at either offset. The cost that does grow with
offset is PostgreSQL walking and discarding the skipped rows before it can return a page — the
scan's `actual time` grows from `0.050..0.054` to `0.349..3.630` because it has to visit all
20,000 matching index entries before it can return the last 20, not just the first 20. That is the
well-known cost of `OFFSET`, not a missing index. Per spec section 9, this is the threshold at
which offset paging is expected to start hurting; even at offset 19980 the real number is 4.572 ms
(single-digit milliseconds), so cursor-based (keyset) paging on `(created_at, id)` is not yet
needed for this data volume — deferred until it actually is, rather than built pre-emptively.
