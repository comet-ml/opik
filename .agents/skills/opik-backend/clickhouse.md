# ClickHouse Patterns

## Schema First
- **Never invent schema** - check migrations: `src/main/resources/liquibase/db-app-analytics/migrations/`
- Find similar queries before writing new ones

## Deduplication (ReplacingMergeTree)

Most tables need deduplication. **Exception**: audit/log tables (ReplicatedMergeTree) keep full history.

Updates are modeled as inserts — multiple row versions coexist until background merge.

```sql
-- Pattern 1: LIMIT 1 BY (reads only referenced columns, requires explicit sort)
SELECT * FROM traces
WHERE workspace_id = :workspace_id
ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
LIMIT 1 BY id

-- Pattern 2: FINAL (query-time merge, streams through sorted parts)
SELECT * FROM traces FINAL
WHERE workspace_id = :workspace_id

-- ❌ BAD - missing deduplication, returns duplicates
SELECT * FROM traces WHERE workspace_id = :workspace_id
```

### FINAL vs LIMIT 1 BY

There's no general rule for which is faster. It depends on the query and table state:

- **LIMIT 1 BY** tends to win when selecting a small subset of columns (columnar advantage)
  or on tables with many unmerged updates (FINAL must merge all versions).
- **FINAL** tends to win on well-merged tables (streams sorted parts without re-sorting)
  or on large result sets (LIMIT 1 BY must explicitly sort all matching rows).

### Mutable column filtering with LIMIT 1 BY

With `LIMIT 1 BY`, filters on mutable columns (`status`, `name`, `tags`, etc.) **MUST go
AFTER dedup** in an outer query. Filtering before dedup can return stale/phantom rows.

```sql
-- ✅ CORRECT                          -- ❌ WRONG
SELECT * FROM (                        SELECT * FROM traces
    SELECT * FROM traces               WHERE status = :status  -- before dedup!
    WHERE workspace_id = :wid          ORDER BY (...) DESC, last_updated_at DESC
    ORDER BY (...) DESC                LIMIT 1 BY id
    LIMIT 1 BY id
) WHERE status = :status
```

Safe to filter before dedup: immutable columns (`workspace_id`, `project_id`, `id`, `created_at`)
and monotonically-changing columns (`last_updated_at` — only increases, so a lower-bound cutoff
can't exclude the latest version while keeping an older one).
With `FINAL`, this doesn't apply — dedup happens before WHERE.

## Skip Indexes in ReplacingMergeTree

**Never index fields that flip back and forth** (e.g., `status`). The index sees old row
versions and can't filter reliably before dedup.

**Monotonically-changing fields are safe** (e.g., `last_updated_at` only increases). Use
`minmax GRANULARITY 1` — ClickHouse skips granules entirely outside the filter range.

Add a migration comment explaining why the indexed field is safe.

### Skip indexes + FINAL

Skip indexes are **ignored** with `FINAL` by default (up to CH 25.3). Enable with:
```sql
SETTINGS use_skip_indexes_if_final=1
```

### Time-bounded FINAL

Avoid bare `FINAL` on large tables. Scope it with a monotonic field + minmax index:

```sql
-- Migration (comment why it's safe):
ALTER TABLE t ADD INDEX idx_last_updated_at last_updated_at TYPE minmax GRANULARITY 1;
ALTER TABLE t MATERIALIZE INDEX idx_last_updated_at;

-- Query: FINAL only considers recent granules
SELECT * FROM (
    SELECT * FROM t FINAL WHERE last_updated_at > now() - INTERVAL 1 DAY
) WHERE status = 'active'
SETTINGS use_skip_indexes_if_final=1
```

## StringTemplate Gotchas

```sql
-- Escape < operator
WHERE id \\<= :uuid_to_time

-- Conditional blocks
<if(project_id)> AND project_id = :project_id <endif>
```

## Parameter Binding

```java
// ✅ GOOD - snake_case
.bind("workspace_id", workspaceId)
.bind("project_id", projectId.toString())

// ❌ BAD - camelCase
.bind("workspaceId", workspaceId)
```

## `FORMAT Values` cells must be literals (OPIK-5694)

Every cell in an `INSERT ... FORMAT Values` per-row tuple must be a plain `:placeholder`
bound to a value the driver serialises as a literal. Function calls, `if(...)`, `::`
casts, `parseDateTime64BestEffort(...)`, `now64(...)`, `mapFromArrays(...)`,
`toDecimal128(...)` etc. trip the fast-path parser. The insert still succeeds, but every
row silently bumps `system.errors` codes 26 / 27 / 43 / 70 and writes to pod stderr —
flooding logs.

Bind the right shape:
- `DateTime64(P)` → `String` formatted via `ClickHouseDateTimeFormat.formatNanos/formatMicros`
  (not `Instant.toString()` — its `T`/`Z` form trips the fast-path).
- `Map(K,V)` → bind a Java `Map`.
- `Decimal128(S)` → bind a `BigDecimal` (`.toString()` would emit a quoted string which
  Decimal cells also reject).
- Non-nullable cell, value null → substitute the column DEFAULT in Java (e.g.
  `Instant.now()` for `now64()`, `'default'` for an Enum8 default).

## Numeric Gotchas

### Handle NaN/Infinity
```sql
toDecimal64(
    greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999),
    9
)
```

### Correct Decimal Precision
- Feedback scores: `Decimal64(9)`
- Cost fields: `Decimal(38, 12)` → use `toDecimal128(..., 12)`

## Sorting / Pagination / Field Exclusion (two-phase + deferred wide columns)

Large `SELECT ... BY project` queries (traces/spans) use a two-phase shape: a light
`page_ids` CTE paginates on id + sort key only, then `page_wide` re-reads the full rows
(including wide text columns: `input`/`output`/`metadata`) for just that page. Wide columns
are deferred (dropped via `EXCEPT`) unless needed.

Invariants to preserve when editing these queries:
- **The pagination pre-filter (`page_ids`) must carry the sort key.** Render `<sort_fields>`
  into `page_ids`' `ORDER BY` (and the final `ORDER BY`), or pagination returns the wrong page
  for custom sorting. `page_wide` is id-bounded + `LIMIT 1 BY id`, so its own order is
  immaterial — the final `SELECT` re-sorts.
- **Keep the sort column available.** When sorting by a wide column, `sort_needs_wide` must keep
  `input`/`output`/`metadata` in the deduped CTE; when excluding a field that is also the sort
  key, the column must still be selectable for the `ORDER BY`. Excluding the sort field must not
  drop or break the sort.
- **Spans and traces share this shape** — change both together.

⚠️ Any change here MUST be covered by full-page-content tests (not id-only) across sortable
fields, custom/dynamic `sort_fields`, and the sort × field-exclusion combination, for BOTH
spans and traces. See `testing.md` → "Sorting / Pagination / Field-Exclusion SQL Changes".

## Performance

- **Prefer subqueries over JOINs** for filtering
- Filter early in CTEs — but only on immutable columns (see above)
- Use `IN (subquery)` pattern
- Batch inserts (1000+ rows)
- Use `LEFT ANY JOIN` when the right table has at most one match per key

## Query Logging
Always add log comment:
```sql
SETTINGS log_comment = '<log_comment>'
```
