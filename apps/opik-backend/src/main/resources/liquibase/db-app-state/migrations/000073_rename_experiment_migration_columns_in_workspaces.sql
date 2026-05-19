--liquibase formatted sql
--changeset thiagohora:000073_rename_experiment_migration_columns_in_workspaces
--comment: Add experiment-project-scoped migration skip columns alongside the existing generic migration_skipped_at/reason columns, and copy any existing data into the new columns. The old columns are kept alive for backward compatibility during rolling deployment; a follow-up migration will drop them after all pods are on the new code.

ALTER TABLE workspaces
    ADD COLUMN experiment_project_migration_skipped_at TIMESTAMP(6) NULL AFTER migration_skipped_reason,
    ADD COLUMN experiment_project_migration_skip_reason VARCHAR(100) NULL AFTER experiment_project_migration_skipped_at;

UPDATE workspaces
SET experiment_project_migration_skipped_at = migration_skipped_at,
    experiment_project_migration_skip_reason = migration_skipped_reason
WHERE migration_skipped_at IS NOT NULL;

CREATE INDEX workspaces_exp_proj_migration_skipped_idx ON workspaces (experiment_project_migration_skipped_at);

--rollback DROP INDEX workspaces_exp_proj_migration_skipped_idx ON workspaces;
--rollback ALTER TABLE workspaces DROP COLUMN experiment_project_migration_skipped_at, DROP COLUMN experiment_project_migration_skip_reason;

