--liquibase formatted sql
--changeset thiagohora:000074_rename_experiment_migration_columns_in_workspaces
--comment: Rename the generic migration_skipped_at/migration_skipped_reason columns to experiment_project_migration_skipped_at/experiment_project_migration_skip_reason to disambiguate from the dataset-project migration columns added in 000074. The migration job is idle outside of migration windows, so an in-place rename is safe.

ALTER TABLE workspaces
    CHANGE COLUMN migration_skipped_at experiment_project_migration_skipped_at TIMESTAMP(6) NULL,
    CHANGE COLUMN migration_skipped_reason experiment_project_migration_skip_reason VARCHAR(255) NULL,
    RENAME INDEX workspaces_migration_skipped_idx TO workspaces_exp_proj_migration_skipped_idx;

--rollback ALTER TABLE workspaces CHANGE COLUMN experiment_project_migration_skipped_at migration_skipped_at TIMESTAMP(6) NULL, CHANGE COLUMN experiment_project_migration_skip_reason migration_skipped_reason VARCHAR(255) NULL, RENAME INDEX workspaces_exp_proj_migration_skipped_idx TO workspaces_migration_skipped_idx;

