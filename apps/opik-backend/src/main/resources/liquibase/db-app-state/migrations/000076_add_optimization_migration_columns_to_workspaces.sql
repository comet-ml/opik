--liquibase formatted sql
--changeset thiagohora:000076_add_optimization_migration_columns_to_workspaces
--comment: Add optimization-project migration skip tracking columns to workspaces table, mirroring the dataset_project_migration_skipped_at/skip_reason columns added in 000075.

ALTER TABLE workspaces
    ADD COLUMN optimization_project_migration_skipped_at TIMESTAMP(6) NULL AFTER dataset_project_migration_skip_reason,
    ADD COLUMN optimization_project_migration_skip_reason VARCHAR(255) NULL AFTER optimization_project_migration_skipped_at;

CREATE INDEX workspaces_opt_proj_migration_skipped_idx ON workspaces (optimization_project_migration_skipped_at);

--rollback DROP INDEX workspaces_opt_proj_migration_skipped_idx ON workspaces;
--rollback ALTER TABLE workspaces DROP COLUMN optimization_project_migration_skipped_at, DROP COLUMN optimization_project_migration_skip_reason;

