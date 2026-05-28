--liquibase formatted sql
--changeset andrescrz:000076_add_prompt_project_migration_skipped_to_workspaces
--comment: Per-entity trap columns for the prompt project migration (D4). Mirrors the experiment (000074) and dataset (000075) trap pairs so the prompt cycle can record its own all_ambiguous trap without colliding with the others. Placed AFTER dataset_project_migration_skip_reason to keep the three trap groups visually adjacent.

ALTER TABLE workspaces
    ADD COLUMN prompt_project_migration_skipped_at TIMESTAMP(6) DEFAULT NULL AFTER dataset_project_migration_skip_reason,
    ADD COLUMN prompt_project_migration_skip_reason VARCHAR(255) DEFAULT NULL AFTER prompt_project_migration_skipped_at,
    ADD INDEX workspaces_prompt_proj_migration_skipped_idx (prompt_project_migration_skipped_at);

--rollback ALTER TABLE workspaces DROP INDEX workspaces_prompt_proj_migration_skipped_idx, DROP COLUMN prompt_project_migration_skip_reason, DROP COLUMN prompt_project_migration_skipped_at;
