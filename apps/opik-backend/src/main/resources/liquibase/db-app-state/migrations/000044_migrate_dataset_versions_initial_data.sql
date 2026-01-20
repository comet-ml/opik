--liquibase formatted sql
--changeset idoberko2:000044_migrate_dataset_versions_initial_data
--comment: Create version 1 for all existing datasets using dataset_id as version_id

-- Step 1: Create version 1 for datasets that don't have versions yet
-- Version ID = Dataset ID (deterministic, no coordination needed with ClickHouse)
INSERT INTO dataset_versions (id, dataset_id, version_hash, workspace_id, items_total, items_added, items_modified, items_deleted, change_description, metadata, created_at, created_by, last_updated_at, last_updated_by)
SELECT 
    d.id,                                    -- version_id = dataset_id (key insight for cross-DB coordination)
    d.id,                                    -- dataset_id
    'v1',                                    -- version_hash (version 1 identifier)
    d.workspace_id,                          -- workspace_id
    0,                                       -- items_total (will be computed by application)
    0,                                       -- items_added (initial version has no delta)
    0,                                       -- items_modified (initial version has no delta)
    0,                                       -- items_deleted (initial version has no delta)
    'Initial version', -- change_description
    NULL,                                    -- metadata (no custom metadata for migration)
    d.created_at,                            -- preserve original creation time
    d.created_by,                            -- preserve original creator
    d.last_updated_at,                       -- preserve last update time
    d.last_updated_by                        -- preserve last updater
FROM datasets d
WHERE d.id NOT IN (SELECT dataset_id FROM dataset_versions);

-- Step 2: Create 'latest' tag for each new version
-- This ensures all datasets have a 'latest' version tag
INSERT INTO dataset_version_tags (workspace_id, dataset_id, tag, version_id, created_at, created_by, last_updated_at, last_updated_by)
SELECT 
    d.workspace_id,                          -- workspace_id
    d.id,                                    -- dataset_id
    'latest',                                -- tag name
    d.id,                                    -- version_id = dataset_id (matches version created above)
    d.created_at,                            -- preserve original creation time
    d.created_by,                            -- preserve original creator
    d.last_updated_at,                       -- preserve last update time
    d.last_updated_by                        -- preserve last updater
FROM datasets d
WHERE d.id NOT IN (
    SELECT dataset_id 
    FROM dataset_version_tags 
    WHERE tag = 'latest'
);

--rollback -- empty
--rollback -- Note: Initial dataset version and 'latest' tag backfill is irreversible and cannot be safely rolled back.
--rollback -- Manual cleanup required if rollback is necessary.

