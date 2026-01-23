--liquibase formatted sql
--changeset thiagohora:000047_add_missing_index_to_dataset_versions
--comment: Add missing index to dataset_versions on (workspace_id, dataset_id, id) for performance improvement

CREATE INDEX idx_dataset_versions_workspace_id_dataset_id_id ON dataset_versions(workspace_id, dataset_id, id);

--rollback DROP INDEX idx_dataset_versions_workspace_id_dataset_id_id ON dataset_versions;
