--liquibase formatted sql
--changeset thiagohora:000114_update_datasets_unique_constraint
--comment: Make dataset names unique per project instead of per workspace. Forward-only: once projects reuse a dataset name, restoring workspace-wide uniqueness needs manual deduplication first

ALTER TABLE datasets ADD CONSTRAINT `datasets_workspace_id_project_id_name_uk` UNIQUE (workspace_id, project_id, name);
ALTER TABLE datasets DROP INDEX `datasets_workspace_id_name_uk`;

--rollback empty

