--liquibase formatted sql
--changeset thiagohora:000075_add_dataset_migration_columns_to_workspaces
--comment: Add dataset-project migration skip tracking columns to workspaces table, mirroring the experiment_project_migration_skipped_at/skip_reason columns renamed in 000073.

ALTER TABLE workspaces
    ADD COLUMN dataset_project_migration_skipped_at TIMESTAMP(6) NULL AFTER experiment_project_migration_skip_reason,
    ADD COLUMN dataset_project_migration_skip_reason VARCHAR(255) NULL AFTER dataset_project_migration_skipped_at;

CREATE INDEX workspaces_ds_proj_migration_skipped_idx ON workspaces (dataset_project_migration_skipped_at);

--rollback DROP INDEX workspaces_ds_proj_migration_skipped_idx ON workspaces;
--rollback ALTER TABLE workspaces DROP COLUMN dataset_project_migration_skipped_at, DROP COLUMN dataset_project_migration_skip_reason;

