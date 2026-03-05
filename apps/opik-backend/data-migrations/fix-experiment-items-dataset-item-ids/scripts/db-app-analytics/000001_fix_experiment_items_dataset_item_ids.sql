-- OPIK-4518: Fix experiment items that stored version-specific row IDs instead of stable dataset_item_ids
--
-- Affected records: experiment_items where dataset_item_id matches a dataset_item_versions.id
-- (the version-specific row ID) rather than the stable dataset_item_id.
-- Only v2+ items are affected: for v1, id == dataset_item_id (migration 000053 set them equal).

-- Step 1: Replace ${ANALYTICS_DB_DATABASE_NAME} with the actual database name throughout this script.

-- Step 2: Run the diagnostic query to gauge impact before applying:

SELECT count() AS affected_experiment_items
FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_items FINAL ei
WHERE ei.dataset_item_id IN (
    SELECT DISTINCT id
    FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions FINAL
    WHERE id != dataset_item_id
);

-- If the count is 0, no migration is needed. Stop here.

-- Step 3: Insert corrected experiment items with stable dataset_item_id.
-- The corrected rows have the same (workspace_id, experiment_id, trace_id, id) but a DIFFERENT
-- dataset_item_id. Because dataset_item_id is part of the ORDER BY key, ReplacingMergeTree
-- treats these as distinct rows — it will NOT deduplicate old vs new. Both will coexist.

INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.experiment_items
    (id, experiment_id, dataset_item_id, trace_id, workspace_id, project_id,
     created_at, last_updated_at, created_by, last_updated_by)
SELECT
    ei.id,
    ei.experiment_id,
    dv.dataset_item_id,
    ei.trace_id,
    ei.workspace_id,
    ei.project_id,
    ei.created_at,
    now64(9),
    ei.created_by,
    ei.last_updated_by
FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_items FINAL ei
INNER JOIN (
    SELECT id AS row_id, dataset_item_id
    FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions FINAL
    WHERE id != dataset_item_id
    LIMIT 1 BY id
) dv ON dv.row_id = ei.dataset_item_id;

-- Step 4: Delete old rows that stored a version-specific row ID as dataset_item_id.
-- This step is REQUIRED — the old and new rows have different ORDER BY keys, so
-- ReplacingMergeTree will never merge them automatically.
-- Uses lightweight DELETE (not ALTER TABLE ... DELETE) to avoid rewriting data parts.
-- Safe because corrected rows have stable dataset_item_id values that are never
-- in (SELECT id WHERE id != dataset_item_id).

DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_items
WHERE dataset_item_id IN (
    SELECT DISTINCT id
    FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions FINAL
    WHERE id != dataset_item_id
);

-- Step 5 (optional): Force merge to physically remove tombstones from the lightweight DELETE.
-- This may take significant time on large tables and can be skipped — ClickHouse will clean
-- up tombstones during normal background merges.

-- OPTIMIZE TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' FINAL;

-- Step 6: Verify the fix by re-running the diagnostic query from Step 2.
-- The count should be 0.

SELECT count() AS still_affected
FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_items FINAL ei
WHERE ei.dataset_item_id IN (
    SELECT DISTINCT id
    FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions FINAL
    WHERE id != dataset_item_id
);
