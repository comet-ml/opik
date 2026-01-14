--liquibase formatted sql
--changeset idoberko2:000044_add_batch_group_id_to_dataset_versions
--comment: Add batch_group_id column to dataset_versions table for SDK batch operation tracking

ALTER TABLE dataset_versions
    ADD COLUMN batch_group_id CHAR(36) DEFAULT NULL;

-- Add index for efficient lookup by batch_group_id
CREATE INDEX idx_dataset_versions_batch_group_id
    ON dataset_versions(batch_group_id);

-- Add composite index for workspace + dataset + batch_group_id lookups
CREATE INDEX idx_dataset_versions_workspace_dataset_batch_group
    ON dataset_versions(workspace_id, dataset_id, batch_group_id);

--rollback DROP INDEX idx_dataset_versions_workspace_dataset_batch_group ON dataset_versions;
--rollback DROP INDEX idx_dataset_versions_batch_group_id ON dataset_versions;
--rollback ALTER TABLE dataset_versions DROP COLUMN batch_group_id;
