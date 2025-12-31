-- Dataset Versioning Migration Test Script
-- Run these queries to validate the migration completed successfully

-- ============================================================================
-- PRE-MIGRATION CHECKS
-- ============================================================================

-- 1. Count existing datasets
SELECT 'Total Datasets' as metric, COUNT(*) as count FROM datasets;

-- 2. Count existing dataset items per dataset
SELECT 
    'Dataset Items by Dataset' as metric,
    dataset_id, 
    COUNT(*) as item_count 
FROM dataset_items 
GROUP BY dataset_id
ORDER BY item_count DESC
LIMIT 10;

-- 3. Check for existing versions (should be minimal before migration)
SELECT 'Existing Versions' as metric, COUNT(*) as count FROM dataset_versions;
SELECT 'Existing Version Tags' as metric, COUNT(*) as count FROM dataset_version_tags;
SELECT 'Existing Versioned Items' as metric, COUNT(*) as count FROM dataset_item_versions;

-- ============================================================================
-- POST-MIGRATION VALIDATION
-- ============================================================================

-- 4. Verify version 1 created for all datasets
-- Should return 0 rows (every dataset should have a version)
SELECT 
    'Datasets Missing Version 1' as validation,
    d.id, 
    d.name, 
    d.workspace_id
FROM datasets d
LEFT JOIN dataset_versions dv ON d.id = dv.dataset_id
WHERE dv.id IS NULL;

-- 5. Verify 'latest' tags created for all datasets
-- Should return 0 rows (every dataset should have a 'latest' tag)
SELECT 
    'Datasets Missing Latest Tag' as validation,
    d.id, 
    d.name, 
    d.workspace_id
FROM datasets d
LEFT JOIN dataset_version_tags dvt ON d.id = dvt.dataset_id AND dvt.tag = 'latest'
WHERE dvt.version_id IS NULL;

-- 6. Verify version ID = dataset ID (key coordination strategy)
-- Should return 0 rows (all version 1 IDs should match dataset IDs)
SELECT 
    'Versions with Mismatched IDs' as validation,
    dataset_id, 
    id as version_id,
    change_description
FROM dataset_versions
WHERE dataset_id != id;

-- 7. Verify all version 1 records have correct metadata
SELECT 
    'Version 1 Metadata Check' as validation,
    id,
    dataset_id,
    version_hash,
    change_description,
    created_at,
    created_by
FROM dataset_versions
WHERE id = dataset_id
ORDER BY created_at DESC
LIMIT 10;

-- 8. Verify all 'latest' tags point to version 1
SELECT 
    'Latest Tag Validation' as validation,
    dvt.dataset_id,
    dvt.tag,
    dvt.version_id,
    dv.change_description
FROM dataset_version_tags dvt
JOIN dataset_versions dv ON dvt.version_id = dv.id
WHERE dvt.tag = 'latest'
ORDER BY dvt.created_at DESC
LIMIT 10;

-- ============================================================================
-- CLICKHOUSE VALIDATION (run in ClickHouse client)
-- ============================================================================

-- 9. Compare item counts between draft and versioned tables
-- Counts should match for each dataset
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

-- 10. Verify dataset_version_id matches dataset_id for all items
-- Should return 0 rows (all items should point to version 1 = dataset_id)
SELECT 
    'Items with Mismatched Version ID' as validation,
    dataset_id, 
    dataset_version_id, 
    COUNT(*) as item_count
FROM dataset_item_versions
WHERE dataset_id != dataset_version_id
GROUP BY dataset_id, dataset_version_id;

-- 11. Verify data integrity - sample check
-- Verify data and metadata match between draft and versioned tables
SELECT 
    'Data Integrity Check' as validation,
    di.id,
    di.dataset_id,
    di.data as draft_data,
    div.data as versioned_data,
    di.metadata as draft_metadata,
    div.metadata as versioned_metadata,
    di.created_at as draft_created_at,
    div.item_created_at as versioned_created_at
FROM dataset_items di
JOIN dataset_item_versions div ON di.id = div.id
WHERE di.dataset_id = div.dataset_id 
  AND div.dataset_version_id = div.dataset_id
LIMIT 10;

-- 12. Verify migration markers
SELECT 
    'Migration Markers Check' as validation,
    dataset_id,
    dataset_version_id,
    created_by,
    last_updated_by,
    COUNT(*) as item_count
FROM dataset_item_versions
WHERE created_by = 'migration' AND last_updated_by = 'migration'
GROUP BY dataset_id, dataset_version_id, created_by, last_updated_by;

-- 13. Verify item identity tracking
-- For version 1, dataset_item_id should equal id
SELECT 
    'Item Identity Check' as validation,
    dataset_id,
    id,
    dataset_item_id,
    dataset_version_id
FROM dataset_item_versions
WHERE dataset_version_id = dataset_id  -- version 1 only
  AND id != dataset_item_id  -- should be equal for version 1
LIMIT 10;

-- ============================================================================
-- SUMMARY STATISTICS
-- ============================================================================

-- 14. MySQL Summary
SELECT 'Migration Summary - MySQL' as report;
SELECT 'Total Datasets' as metric, COUNT(*) as count FROM datasets;
SELECT 'Total Versions' as metric, COUNT(*) as count FROM dataset_versions;
SELECT 'Version 1 Records' as metric, COUNT(*) as count FROM dataset_versions WHERE id = dataset_id;
SELECT 'Latest Tags' as metric, COUNT(*) as count FROM dataset_version_tags WHERE tag = 'latest';

-- 15. ClickHouse Summary (run in ClickHouse client)
SELECT 'Migration Summary - ClickHouse' as report;
SELECT 'Total Draft Items' as metric, COUNT(*) as count FROM dataset_items;
SELECT 'Total Versioned Items' as metric, COUNT(*) as count FROM dataset_item_versions;
SELECT 'Version 1 Items' as metric, COUNT(*) as count FROM dataset_item_versions WHERE dataset_version_id = dataset_id;
SELECT 'Migrated Items' as metric, COUNT(*) as count FROM dataset_item_versions WHERE created_by = 'migration';

-- ============================================================================
-- ROLLBACK VERIFICATION (run after rollback if needed)
-- ============================================================================

-- 16. Verify rollback completed (should return 0 for all)
SELECT 'Post-Rollback Check - MySQL' as report;
SELECT 'Migrated Versions Remaining' as metric, COUNT(*) as count 
FROM dataset_versions 
WHERE change_description = 'Initial version (migrated from draft)';

SELECT 'Latest Tags Remaining' as metric, COUNT(*) as count 
FROM dataset_version_tags 
WHERE tag = 'latest' AND version_id = dataset_id;

-- 17. ClickHouse Rollback Check (run in ClickHouse client)
SELECT 'Post-Rollback Check - ClickHouse' as report;
SELECT 'Migrated Items Remaining' as metric, COUNT(*) as count 
FROM dataset_item_versions 
WHERE created_by = 'migration';

