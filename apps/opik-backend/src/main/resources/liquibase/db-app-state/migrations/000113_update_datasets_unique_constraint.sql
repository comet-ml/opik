--liquibase formatted sql
--changeset thiagohora:000113_update_datasets_unique_constraint
--comment: Make dataset names unique per project instead of per workspace. The key leads with (workspace_id, name) so name lookups without a project still use it.

ALTER TABLE datasets ADD CONSTRAINT `datasets_workspace_id_name_project_id_uk` UNIQUE (workspace_id, name, project_id);
ALTER TABLE datasets DROP INDEX `datasets_workspace_id_name_uk`;

--rollback -- Rollback is intentionally a no-op. Reinstating the workspace-wide unique key fails once a workspace
--rollback -- has the same dataset name in two projects. Rename or remove those duplicates manually first.
