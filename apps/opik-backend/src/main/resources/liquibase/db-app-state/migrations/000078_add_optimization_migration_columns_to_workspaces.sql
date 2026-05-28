--liquibase formatted sql
--changeset thiagohora:000078_add_optimization_migration_columns_to_workspaces
--comment: Per-entity trap columns for the optimization project migration (D3). Mirrors the experiment (000074) and prompt (000076) trap pairs so the optimization cycle can record its own all_ambiguous trap without colliding with the others. Placed AFTER prompt_project_migration_skip_reason to keep the trap groups visually adjacent.

ALTER TABLE workspaces
    ADD COLUMN optimization_project_migration_skipped_at TIMESTAMP(6) NULL AFTER prompt_project_migration_skip_reason,
    ADD COLUMN optimization_project_migration_skip_reason VARCHAR(255) NULL AFTER optimization_project_migration_skipped_at;

-- Index for the optimization-migration job's eligibility scan: filters out workspaces already
-- marked skipped (WHERE optimization_project_migration_skipped_at IS NULL) so the runner can
-- pick the next batch without scanning the full workspaces table.
CREATE INDEX workspaces_opt_proj_migration_skipped_idx ON workspaces (optimization_project_migration_skipped_at);

--rollback DROP INDEX workspaces_opt_proj_migration_skipped_idx ON workspaces;
--rollback ALTER TABLE workspaces DROP COLUMN optimization_project_migration_skipped_at, DROP COLUMN optimization_project_migration_skip_reason;

