# Adding New Filter Field Types

Checklist for adding a new `FieldType` (e.g., `STRING_EXACT`) or changing an existing field's type in the filter system.

## When to Use STRING_EXACT vs STRING

**ID fields MUST use `FieldType.STRING_EXACT`** instead of `FieldType.STRING`. This is critical for both correctness and performance:

- **Performance**: `STRING` wraps queries in `lower()`, which prevents ClickHouse from using primary key indexes on ID columns. This forces full table scans on large tables, causing slow queries and high CPU usage in production.
- **Correctness**: UUIDs and other ID values are case-sensitive by nature. Applying `lower()` is unnecessary and produces wrong results if IDs contain mixed-case characters.

**Rule of thumb**: Any field that stores a UUID, foreign key, or system-generated identifier (ID, TRACE_ID, SPAN_ID, THREAD_ID, PROJECT_ID, DATASET_ID, EXPERIMENT_ID) should use `STRING_EXACT`. Only human-readable text fields (NAME, INPUT, OUTPUT, ERROR_TYPE, MODEL, PROVIDER, DESCRIPTION) should use `STRING` for case-insensitive matching.

When adding a new filterable field to any Field enum, ask: "Is this an identifier or user-facing text?" If it's an identifier, use `STRING_EXACT`.

## Files That Must Be Updated

### 1. `FieldType.java`
Add the new enum value with its serialized name.

```java
STRING_EXACT("string_exact"),
```

### 2. `FiltersFactory.java` — Validation Map
Add an entry to `FIELD_TYPE_VALIDATION_MAP`. Without this, `validateFieldType()` throws NPE.

```java
.put(FieldType.STRING_EXACT, filter -> StringUtils.isNotBlank(filter.value()))
```

### 3. `FiltersFactory.java` — URL Decoding
If the new type represents values already decoded during JSON deserialization (like strings and IDs), add it to the skip-decode condition in `toValidAndDecoded()`:

```java
if (filter.field().getType() != FieldType.STRING
        && filter.field().getType() != FieldType.STRING_EXACT) {
    // URL decode...
}
```

Without this, values containing `+` or `%` get corrupted by double-decoding.

### 4. `FilterQueryBuilder.java` — All Operator Maps
Add entries for the new type in `ANALYTICS_DB_OPERATOR_MAP` for **every operator** the type should support:

- `Operator.CONTAINS`
- `Operator.NOT_CONTAINS`
- `Operator.STARTS_WITH`
- `Operator.ENDS_WITH`
- `Operator.EQUAL`
- `Operator.NOT_EQUAL`
- `Operator.GREATER_THAN`
- `Operator.LESS_THAN`

Missing an operator entry causes `toAnalyticsDbOperator()` to return null, which results in a 400 error when that operator is used.

### 5. Field Enum Classes
Update the specific field enum to use the new type:

- `SpanField.java`
- `TraceField.java`
- `TraceThreadField.java`
- `DatasetItemField.java`
- `ExperimentsComparisonValidKnownField.java`
- `AnnotationQueueField.java`
- `ExperimentField.java`
- `OptimizationField.java`

## Common Mistakes

- **Forgetting the validation map entry** — causes `NullPointerException` in `validateFieldType()` at runtime (500 error)
- **Forgetting operator entries** — causes 400 "Invalid operator" errors for specific operator/type combinations
- **Forgetting URL-decode skip** — corrupts filter values containing `+` or `%` characters
- **Only adding EQUAL/NOT_EQUAL** — the UI exposes contains, starts with, ends with operators too; all need entries

## Testing

When adding a new field type, test all filter operators from the UI:
1. Navigate to the relevant page (Traces, Spans, Datasets, etc.)
2. Open the filter panel
3. Select the field, try each operator (=, contains, starts with, ends with, doesn't contain)
4. Verify results are returned (no 500/400 errors)
5. Check backend logs for any `NullPointerException` or `BadRequestException`

## Reference PR

OPIK-5154 — Added `STRING_EXACT` for case-sensitive ID field filtering.
