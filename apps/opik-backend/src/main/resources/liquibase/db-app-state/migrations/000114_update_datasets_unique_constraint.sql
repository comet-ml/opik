--liquibase formatted sql
--changeset thiagohora:000114_update_datasets_unique_constraint
--comment: Make dataset names unique per project instead of per workspace

ALTER TABLE datasets ADD CONSTRAINT `datasets_workspace_id_project_id_name_uk` UNIQUE (workspace_id, project_id, name);
ALTER TABLE datasets DROP INDEX `datasets_workspace_id_name_uk`;

--rollback ALTER TABLE datasets ADD CONSTRAINT `datasets_workspace_id_name_uk` UNIQUE (workspace_id, name);
--rollback ALTER TABLE datasets DROP INDEX `datasets_workspace_id_project_id_name_uk`;
