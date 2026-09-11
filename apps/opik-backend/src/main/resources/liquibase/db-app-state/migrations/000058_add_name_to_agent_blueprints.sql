--liquibase formatted sql
--changeset borystkachenko:000058_add_name_to_agent_blueprints
--comment: Add name column to agent_blueprints for versioned naming (v1, v2, ...)

ALTER TABLE agent_blueprints ADD COLUMN name VARCHAR(32) NOT NULL DEFAULT 'v1';

DROP INDEX agent_blueprint_workspace_config_id_uk ON agent_blueprints;

CREATE INDEX idx_agent_blueprints_workspace_project_name ON agent_blueprints (workspace_id, project_id, name);

--rollback DROP INDEX idx_agent_blueprints_workspace_project_name ON agent_blueprints;
--rollback CREATE UNIQUE INDEX agent_blueprint_workspace_config_id_uk ON agent_blueprints (workspace_id, project_id, id);
--rollback ALTER TABLE agent_blueprints DROP COLUMN name;
