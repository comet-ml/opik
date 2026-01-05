# Dataset Versioning Migration Guide

## Overview

This document describes the data migration strategy for introducing automatic versioning to the Opik dataset system. The migration creates version 1 for all existing datasets and copies draft items to the versioned storage.

## Migration Strategy

### Key Design Decision: Version 1 ID = Dataset ID

The migration uses a **deterministic ID strategy** where the first version of each dataset uses the dataset's own ID as the version ID. This eliminates the need for cross-database coordination between MySQL and ClickHouse.

**Benefits:**
- Pure SQL migrations (no application code needed)
- Independent execution (MySQL and ClickHouse run separately)
- Idempotent (safe to re-run)
- Deterministic (version IDs are predictable)

## Migration Files

### 1. MySQL Migration
**File:** `db-app-state/migrations/000042_migrate_dataset_versions_initial_data.sql`

**What it does:**
1. Creates a version 1 record for each dataset in `dataset_versions` table
   - Uses `dataset_id` as the `version_id` (key coordination point)
   - Sets change_description to "Initial version (migrated from draft)"
   - Preserves original creation timestamps and creators

2. Creates a 'latest' tag for each version in `dataset_version_tags` table
   - Points to the version 1 created above
   - Ensures all datasets have a 'latest' version

**Idempotency:**
- Uses `WHERE d.id NOT IN (SELECT dataset_id FROM dataset_versions)` to avoid duplicates
- Safe to re-run if migration fails partway through

### 2. ClickHouse Migration
**File:** `db-app-analytics/migrations/000050_migrate_dataset_item_versions_initial_data.sql`

**What it does:**
1. Copies all items from `dataset_items` (draft table) to `dataset_item_versions`
   - Uses `dataset_id` as `dataset_version_id` (matches MySQL version 1)
   - Sets `dataset_item_id = id` for version 1 (establishes item identity)
   - Preserves all item data, metadata, and timestamps
   - Sets migration metadata (created_by = 'migration')

**Idempotency:**
- Uses `WHERE dataset_id NOT IN (SELECT DISTINCT dataset_id FROM dataset_item_versions)` to avoid duplicates
- Safe to re-run if migration fails partway through

## Data Flow

```
MySQL (db-app-state):
  datasets table
    ↓ (migration 000042)
  dataset_versions table (version_id = dataset_id)
    ↓
  dataset_version_tags table (tag = 'latest', version_id = dataset_id)

ClickHouse (db-app-analytics):
  dataset_items table (draft)
    ↓ (migration 000050)
  dataset_item_versions table (dataset_version_id = dataset_id)
```

## Schema Mapping

### MySQL: dataset_versions
| Column | Source | Notes |
|--------|--------|-------|
| `id` | `datasets.id` | **Version ID = Dataset ID** |
| `dataset_id` | `datasets.id` | Links to parent dataset |
| `version_hash` | `'v1'` | Version 1 identifier |
| `workspace_id` | `datasets.workspace_id` | Workspace isolation |
| `items_total` | `0` | Will be computed by application |
| `items_added` | `0` | No delta for initial version |
| `items_modified` | `0` | No delta for initial version |
| `items_deleted` | `0` | No delta for initial version |
| `change_description` | `'Initial version (migrated from draft)'` | Migration marker |
| `metadata` | `NULL` | No custom metadata |
| `created_at` | `datasets.created_at` | Preserve original time |
| `created_by` | `datasets.created_by` | Preserve original creator |

### ClickHouse: dataset_item_versions
| Column | Source | Notes |
|--------|--------|-------|
| `id` | `dataset_items.id` | Preserve original row ID |
| `dataset_item_id` | `dataset_items.id` | **Item identity = ID for v1** |
| `dataset_id` | `dataset_items.dataset_id` | Links to dataset |
| `dataset_version_id` | `dataset_items.dataset_id` | **= Dataset ID (matches MySQL)** |
| `data` | `dataset_items.data` | Preserve data map |
| `metadata` | `dataset_items.metadata` | Preserve metadata |
| `source` | `dataset_items.source` | Preserve source enum |
| `trace_id` | `dataset_items.trace_id` | Preserve trace link |
| `span_id` | `dataset_items.span_id` | Preserve span link |
| `tags` | `dataset_items.tags` | Preserve tags array |
| `item_created_at` | `dataset_items.created_at` | When item was created |
| `item_last_updated_at` | `dataset_items.last_updated_at` | When item was modified |
| `item_created_by` | `dataset_items.created_by` | Original creator |
| `item_last_updated_by` | `dataset_items.last_updated_by` | Last modifier |
| `created_at` | `dataset_items.created_at` | Preserve original creation time |
| `created_by` | `dataset_items.created_by` | Preserve original creator |
| `workspace_id` | `dataset_items.workspace_id` | Workspace isolation |

## Testing the Migration

### Pre-Migration Checks

1. **Count existing datasets:**
   ```sql
   -- MySQL
   SELECT COUNT(*) FROM datasets;
   ```

2. **Count existing dataset items:**
   ```sql
   -- ClickHouse
   SELECT dataset_id, COUNT(*) as item_count 
   FROM dataset_items 
   GROUP BY dataset_id;
   ```

3. **Check for existing versions (should be empty or minimal):**
   ```sql
   -- MySQL
   SELECT COUNT(*) FROM dataset_versions;
   SELECT COUNT(*) FROM dataset_version_tags;
   
   -- ClickHouse
   SELECT COUNT(*) FROM dataset_item_versions;
   ```

### Running the Migration

```bash
# From apps/opik-backend directory
mvn liquibase:update
```

### Post-Migration Validation

1. **Verify version 1 created for all datasets:**
   ```sql
   -- MySQL: Check that every dataset has a version
   SELECT d.id, d.name, dv.id as version_id, dv.change_description
   FROM datasets d
   LEFT JOIN dataset_versions dv ON d.id = dv.dataset_id
   WHERE dv.id IS NULL;
   -- Should return 0 rows
   ```

2. **Verify 'latest' tags created:**
   ```sql
   -- MySQL: Check that every dataset has a 'latest' tag
   SELECT d.id, d.name, dvt.tag, dvt.version_id
   FROM datasets d
   LEFT JOIN dataset_version_tags dvt ON d.id = dvt.dataset_id AND dvt.tag = 'latest'
   WHERE dvt.version_id IS NULL;
   -- Should return 0 rows
   ```

3. **Verify version ID = dataset ID:**
   ```sql
   -- MySQL: Confirm the key coordination strategy
   SELECT dataset_id, id as version_id
   FROM dataset_versions
   WHERE dataset_id != id;
   -- Should return 0 rows (all version 1 IDs match dataset IDs)
   ```

4. **Verify items copied to versioned table:**
   ```sql
   -- ClickHouse: Compare item counts
   SELECT 
       'draft' as source,
       dataset_id,
       COUNT(*) as item_count
   FROM dataset_items
   GROUP BY dataset_id
   
   UNION ALL
   
   SELECT 
       'versioned' as source,
       dataset_id,
       COUNT(*) as item_count
   FROM dataset_item_versions
   WHERE dataset_version_id = dataset_id  -- version 1 only
   GROUP BY dataset_id
   ORDER BY dataset_id, source;
   -- Counts should match for each dataset
   ```

5. **Verify dataset_version_id matches dataset_id:**
   ```sql
   -- ClickHouse: Confirm coordination with MySQL
   SELECT dataset_id, dataset_version_id, COUNT(*) as item_count
   FROM dataset_item_versions
   WHERE dataset_version_id = dataset_id  -- version 1 items
   GROUP BY dataset_id, dataset_version_id;
   -- Should return counts for all datasets
   ```

6. **Verify data integrity:**
   ```sql
   -- ClickHouse: Sample check that data was preserved
   SELECT 
       di.id,
       di.data as draft_data,
       div.data as versioned_data,
       di.metadata as draft_metadata,
       div.metadata as versioned_metadata
   FROM dataset_items di
   JOIN dataset_item_versions div ON di.id = div.id
   WHERE di.dataset_id = div.dataset_id 
     AND div.dataset_version_id = div.dataset_id
   LIMIT 10;
   -- Verify data and metadata match
   ```

### Rollback Testing

If you need to test rollback:

```bash
# Rollback the last changeset
mvn liquibase:rollback -Dliquibase.rollbackCount=1

# Or rollback to a specific tag
mvn liquibase:rollback -Dliquibase.rollbackTag=<tag>
```

**Verify rollback:**
```sql
-- MySQL: Versions should be removed
SELECT COUNT(*) FROM dataset_versions WHERE change_description = 'Initial version (migrated from draft)';
-- Should return 0

-- MySQL: Latest tags should be removed
SELECT COUNT(*) FROM dataset_version_tags WHERE tag = 'latest' AND version_id = dataset_id;
-- Should return 0

-- ClickHouse: Versioned items should be removed
SELECT COUNT(*) FROM dataset_item_versions WHERE dataset_version_id = dataset_id;
-- Should return 0
```

## Common Issues and Solutions

### Issue: Migration runs but creates no records

**Cause:** Data already migrated (idempotency working correctly)

**Solution:** Check if versions already exist:
```sql
SELECT COUNT(*) FROM dataset_versions WHERE id = dataset_id;
```

### Issue: Item counts don't match between draft and versioned tables

**Cause:** Items were added/modified during migration

**Solution:** 
1. Check timestamps to identify new items
2. Re-run migration (idempotent - will only migrate new datasets)
3. Or manually create versions for new items using the application

### Issue: ClickHouse migration fails with "dataset_id not found"

**Cause:** MySQL migration didn't complete or ClickHouse ran first

**Solution:**
1. Verify MySQL migration completed: `SELECT COUNT(*) FROM dataset_versions`
2. Re-run migrations in order: `mvn liquibase:update`

## Performance Considerations

### Expected Migration Time

- **Small installations** (<1000 datasets, <100K items): < 1 minute
- **Medium installations** (1000-10K datasets, 100K-1M items): 1-5 minutes
- **Large installations** (>10K datasets, >1M items): 5-30 minutes

### Resource Usage

- **MySQL:** Minimal impact (simple INSERT SELECT)
- **ClickHouse:** Moderate I/O (copying all items to new table)
- **Disk Space:** Temporary doubling of dataset_items storage (until draft table deprecated)

### Production Recommendations

1. **Schedule during low-traffic period**
2. **Monitor ClickHouse disk space** (ensure 2x current dataset_items size available)
3. **Run MySQL migration first** (creates version metadata)
4. **Run ClickHouse migration second** (copies item data)
5. **Validate thoroughly** before enabling feature toggle

## Next Steps

After successful migration:

1. ✅ Verify all validation queries pass
2. ✅ Enable feature toggle for dataset versioning
3. ✅ Test new versioning endpoints
4. ✅ Monitor application logs for errors
5. ✅ Gradually roll out to users

## Related Documentation

- [Dataset Versioning Refactor Plan](/.cursor/plans/dataset_versioning_refactor_c1f6fcd7.plan.md)
- [Database Migration Guidelines](/.cursor/rules/apps/opik-backend/db_migration_script.mdc)
- [Liquibase Documentation](https://www.liquibase.org/documentation/)

