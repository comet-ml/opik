--liquibase formatted sql
--changeset borystkachenko:000064_refactor_agent_blueprints_values
--comment: Store full key/value parameters per blueprint as JSON; drop agent_config_values table

ALTER TABLE agent_blueprints ADD COLUMN `values` JSON NOT NULL DEFAULT (JSON_ARRAY());

DROP TABLE IF EXISTS agent_config_values;

--rollback CREATE TABLE IF NOT EXISTS agent_config_values (
--rollback     id CHAR(36) NOT NULL,
--rollback     workspace_id VARCHAR(150) NOT NULL,
--rollback     project_id CHAR(36) NOT NULL,
--rollback     config_id CHAR(36) NOT NULL,
--rollback     `key` VARCHAR(255) NOT NULL,
--rollback     `value` TEXT NULL,
--rollback     type ENUM('string', 'integer', 'float', 'boolean', 'prompt', 'prompt_commit', 'python_prompt') NOT NULL DEFAULT 'string',
--rollback     description TEXT,
--rollback     valid_from_blueprint_id CHAR(36) NOT NULL,
--rollback     valid_to_blueprint_id CHAR(36),
--rollback     CONSTRAINT agent_config_values_pk PRIMARY KEY (id),
--rollback     CONSTRAINT agent_config_values_workspace_blueprint_key_uk UNIQUE (workspace_id, project_id, valid_from_blueprint_id, valid_to_blueprint_id, `key`),
--rollback     INDEX idx_acv_workspace_type_value (workspace_id, type, `value`(150))
--rollback );
--rollback ALTER TABLE agent_blueprints DROP COLUMN `values`;
