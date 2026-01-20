--liquibase formatted sql
--changeset idoberko2:000053_migrate_dataset_item_versions_initial_data
--comment: Copy items from dataset_items (legacy) to dataset_item_versions for version 1

-- Step 3: Copy items from legacy table to versioned table
-- dataset_version_id = dataset_id (matches version 1 ID from MySQL migration)
-- This creates immutable snapshots of all existing legacy items
INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions
(
    id,
    dataset_item_id,
    dataset_id,
    dataset_version_id,
    data,
    metadata,
    source,
    trace_id,
    span_id,
    tags,
    item_created_at,
    item_last_updated_at,
    item_created_by,
    item_last_updated_by,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    workspace_id
)
SELECT
    id,                                      -- preserve original row ID
    id,                                      -- dataset_item_id = id (for version 1, item identity = row ID)
    dataset_id,                              -- dataset_id
    dataset_id,                              -- dataset_version_id = dataset_id (matches MySQL version 1)
    data,                                    -- preserve data map
    metadata,                                -- preserve metadata
    source,                                  -- preserve source enum
    trace_id,                                -- preserve trace_id
    span_id,                                 -- preserve span_id
    tags,                                    -- preserve tags array
    created_at,                              -- item_created_at (when item was first created)
    last_updated_at,                         -- item_last_updated_at (when item was last modified)
    created_by,                              -- item_created_by
    last_updated_by,                         -- item_last_updated_by
    created_at,                              -- created_at (preserve original creation time)
    last_updated_at,                         -- last_updated_at (preserve original update time)
    created_by,                              -- created_by (preserve original creator)
    last_updated_by,                         -- last_updated_by (preserve original updater)
    workspace_id                             -- workspace_id
FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
WHERE dataset_id NOT IN (
    SELECT DISTINCT dataset_id 
    FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions
);

--rollback -- empty
--rollback -- Note: Backfilled version-1 snapshots cannot be safely rolled back. Manual cleanup required if needed.

