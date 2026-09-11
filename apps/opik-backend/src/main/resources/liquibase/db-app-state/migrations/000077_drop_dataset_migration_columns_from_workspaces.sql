--liquibase formatted sql
--changeset andrescrz:000077_drop_dataset_migration_columns_from_workspaces
--comment: Drop the dataset-project migration trap columns and index from workspaces. The job now assigns multi-project orphans to a dominant project instead of trapping, so the trap state is unreachable. Reverses 000075; experiment (000074) and prompt (000076) trap columns are untouched.

DROP INDEX workspaces_ds_proj_migration_skipped_idx ON workspaces;

ALTER TABLE workspaces
    DROP COLUMN dataset_project_migration_skipped_at,
    DROP COLUMN dataset_project_migration_skip_reason;

--rollback ALTER TABLE workspaces
--rollback     ADD COLUMN dataset_project_migration_skipped_at TIMESTAMP(6) NULL AFTER experiment_project_migration_skip_reason,
--rollback     ADD COLUMN dataset_project_migration_skip_reason VARCHAR(255) NULL AFTER dataset_project_migration_skipped_at;
--rollback CREATE INDEX workspaces_ds_proj_migration_skipped_idx ON workspaces (dataset_project_migration_skipped_at);

