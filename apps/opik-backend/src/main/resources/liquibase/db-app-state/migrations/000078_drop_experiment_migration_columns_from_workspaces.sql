--liquibase formatted sql
--changeset andrescrz:000078_drop_experiment_migration_columns_from_workspaces
--comment: Drop the experiment-project migration trap columns and index from workspaces. The job now assigns multi-project orphans to a dominant project, so the trap state is unreachable. Prompt trap columns (000076) are untouched.

DROP INDEX workspaces_exp_proj_migration_skipped_idx ON workspaces;

ALTER TABLE workspaces
    DROP COLUMN experiment_project_migration_skipped_at,
    DROP COLUMN experiment_project_migration_skip_reason;

--rollback ALTER TABLE workspaces
--rollback     ADD COLUMN experiment_project_migration_skipped_at TIMESTAMP(6) NULL AFTER first_trace_reported_at,
--rollback     ADD COLUMN experiment_project_migration_skip_reason VARCHAR(255) NULL AFTER experiment_project_migration_skipped_at;
--rollback CREATE INDEX workspaces_exp_proj_migration_skipped_idx ON workspaces (experiment_project_migration_skipped_at);
