# Dataset Versioning Migration - Quick Start Guide

## TL;DR

Two SQL migrations create version 1 for all existing datasets:
- **MySQL**: Creates version records and 'latest' tags
- **ClickHouse**: Copies draft items to versioned storage

**Key Insight:** Version 1 ID = Dataset ID (enables cross-DB coordination)

## Running the Migration

```bash
cd apps/opik-backend
mvn liquibase:update
```

## Quick Validation

### MySQL
```sql
-- Every dataset should have a version
SELECT COUNT(*) FROM datasets;
SELECT COUNT(*) FROM dataset_versions WHERE id = dataset_id;
-- These counts should match

-- Every dataset should have a 'latest' tag
SELECT COUNT(*) FROM dataset_version_tags WHERE tag = 'latest';
-- Should equal dataset count
```

### ClickHouse
```sql
-- Item counts should match
SELECT dataset_id, COUNT(*) FROM dataset_items GROUP BY dataset_id;
SELECT dataset_id, COUNT(*) FROM dataset_item_versions WHERE dataset_version_id = dataset_id GROUP BY dataset_id;
-- Counts should match per dataset
```

## Files Created

1. **`000042_migrate_dataset_versions_initial_data.sql`** (MySQL)
   - Creates version 1 records in `dataset_versions`
   - Creates 'latest' tags in `dataset_version_tags`
   - Idempotent (safe to re-run)

2. **`000050_migrate_dataset_item_versions_initial_data.sql`** (ClickHouse)
   - Copies items from `dataset_items` to `dataset_item_versions`
   - Sets `dataset_version_id = dataset_id` (matches MySQL version 1)
   - Idempotent (safe to re-run)

3. **`DATASET_VERSIONING_MIGRATION.md`**
   - Comprehensive migration guide
   - Detailed validation queries
   - Troubleshooting tips

4. **`test_dataset_versioning_migration.sql`**
   - Pre-migration checks
   - Post-migration validation
   - Rollback verification

## Rollback

```bash
cd apps/opik-backend
mvn liquibase:rollback -Dliquibase.rollbackCount=2
```

Verify rollback:
```sql
-- MySQL: Should return 0
SELECT COUNT(*) FROM dataset_versions WHERE change_description = 'Initial version (migrated from draft)';

-- ClickHouse: Should return 0
SELECT COUNT(*) FROM dataset_item_versions WHERE created_by = 'migration';
```

## Common Issues

**Q: Migration creates no records**
- A: Data already migrated (idempotency working). Check: `SELECT COUNT(*) FROM dataset_versions WHERE id = dataset_id;`

**Q: Item counts don't match**
- A: Items added during migration. Re-run (idempotent) or create versions via application.

**Q: ClickHouse fails with "dataset_id not found"**
- A: MySQL migration didn't complete. Verify: `SELECT COUNT(*) FROM dataset_versions;` then re-run.

## Next Steps

1. ✅ Run migration: `mvn liquibase:update`
2. ✅ Validate using queries in `test_dataset_versioning_migration.sql`
3. ✅ Enable feature toggle for dataset versioning
4. ✅ Test new versioning endpoints

## Need More Details?

See [DATASET_VERSIONING_MIGRATION.md](./DATASET_VERSIONING_MIGRATION.md) for:
- Complete schema mapping
- Detailed validation queries
- Performance considerations
- Production recommendations

