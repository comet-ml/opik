--liquibase formatted sql
--changeset boryst:000089_update_prompts_unique_constraint

ALTER TABLE prompts ADD CONSTRAINT `prompts_workspace_id_project_id_name_uk` UNIQUE (workspace_id, project_id, name);
ALTER TABLE prompts DROP INDEX `prompts_workspace_id_name_uk`;

--rollback ALTER TABLE prompts ADD CONSTRAINT `prompts_workspace_id_name_uk` UNIQUE (workspace_id, name);
--rollback ALTER TABLE prompts DROP INDEX `prompts_workspace_id_project_id_name_uk`;
