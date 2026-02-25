# ClickHouse Patterns

## Schema First
- **Never invent schema** - check migrations: `src/main/resources/liquibase/db-app-analytics/migrations/`
- Find similar queries before writing new ones

## Deduplication (ReplacingMergeTree)

Most tables need deduplication. **Exception**: audit/log tables (ReplicatedMergeTree) keep full history.

```sql
-- Pattern 1: LIMIT 1 BY (most common, better performance)
SELECT * FROM traces
WHERE workspace_id = :workspace_id
ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
LIMIT 1 BY id

-- Pattern 2: FINAL (simpler syntax)
SELECT * FROM traces FINAL
WHERE workspace_id = :workspace_id

-- ❌ BAD - missing deduplication, returns duplicates
SELECT * FROM traces WHERE workspace_id = :workspace_id
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
- Filter early in CTEs
- Use `IN (subquery)` pattern
- Batch inserts (1000+ rows)

## Query Logging
Always add log comment:
```sql
SETTINGS log_comment = '<log_comment>'
```
