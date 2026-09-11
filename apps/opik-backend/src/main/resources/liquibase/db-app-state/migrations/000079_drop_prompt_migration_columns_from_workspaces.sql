--liquibase formatted sql
--changeset andrescrz:000079_drop_prompt_migration_columns_from_workspaces
--comment: Drop the prompt-project migration trap columns and index from workspaces. The job now assigns multi-project orphans to a dominant project, so the trap state is unreachable.

DROP INDEX workspaces_prompt_proj_migration_skipped_idx ON workspaces;

ALTER TABLE workspaces
    DROP COLUMN prompt_project_migration_skipped_at,
    DROP COLUMN prompt_project_migration_skip_reason;

--rollback ALTER TABLE workspaces
--rollback     ADD COLUMN prompt_project_migration_skipped_at TIMESTAMP(6) NULL AFTER first_trace_reported_at,
--rollback     ADD COLUMN prompt_project_migration_skip_reason VARCHAR(255) NULL AFTER prompt_project_migration_skipped_at;
--rollback CREATE INDEX workspaces_prompt_proj_migration_skipped_idx ON workspaces (prompt_project_migration_skipped_at);
