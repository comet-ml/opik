--liquibase formatted sql
--changeset thiagohora:000057_add_project_id_to_entity_tables
--comment: Add nullable project_id columns to datasets, prompts, and dashboards tables for project-scoped operations

ALTER TABLE datasets
    ADD COLUMN project_id CHAR(36) NULL DEFAULT NULL;

ALTER TABLE prompts
    ADD COLUMN project_id CHAR(36) NULL DEFAULT NULL;

ALTER TABLE dashboards
    ADD COLUMN project_id CHAR(36) NULL DEFAULT NULL;

CREATE INDEX datasets_workspace_project_idx ON datasets (workspace_id, project_id);
CREATE INDEX prompts_workspace_project_idx ON prompts (workspace_id, project_id);
CREATE INDEX dashboards_workspace_project_idx ON dashboards (workspace_id, project_id);

--rollback DROP INDEX dashboards_workspace_project_idx ON dashboards;
--rollback DROP INDEX prompts_workspace_project_idx ON prompts;
--rollback DROP INDEX datasets_workspace_project_idx ON datasets;
--rollback ALTER TABLE datasets DROP COLUMN project_id;
--rollback ALTER TABLE prompts DROP COLUMN project_id;
--rollback ALTER TABLE dashboards DROP COLUMN project_id;
