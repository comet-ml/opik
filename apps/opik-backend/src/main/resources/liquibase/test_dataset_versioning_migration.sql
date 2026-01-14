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

-- 3. Check for existing versions (should be empty before migration)
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

-- 6. Verify all version 1 records have correct metadata
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

-- 7. Verify all 'latest' tags point to version 1
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

-- 1. Verify dataset_version_id matches dataset_id for all items
-- Should return 0 rows (all items should point to version 1 = dataset_id)
SELECT 
    'Items with Mismatched Version ID' as validation,
    dataset_id, 
    dataset_version_id, 
    COUNT(*) as item_count
FROM dataset_item_versions
WHERE dataset_id != dataset_version_id
GROUP BY dataset_id, dataset_version_id;

-- 2. Verify data integrity - sample check
-- Verify data and metadata match between legacy and versioned tables
SELECT 
    'Data Integrity Check' as validation,
    di.id,
    di.dataset_id,
    di.data as legacy_data,
    div.data as versioned_data,
    di.metadata as legacy_metadata,
    div.metadata as versioned_metadata,
    di.created_at as legacy_created_at,
    div.item_created_at as versioned_created_at
FROM dataset_items di
JOIN dataset_item_versions div ON di.id = div.id
WHERE di.dataset_id = div.dataset_id 
  AND div.dataset_version_id = div.dataset_id
LIMIT 10;
