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
