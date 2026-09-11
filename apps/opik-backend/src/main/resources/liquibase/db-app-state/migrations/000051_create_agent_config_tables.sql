--liquibase formatted sql
--changeset borystkachenko:000051_create_agent_config_tables
--comment: Create agent configuration tables

CREATE TABLE IF NOT EXISTS agent_configs (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT agent_config_pk PRIMARY KEY (id),
    CONSTRAINT agent_config_workspace_id_uk UNIQUE (workspace_id, project_id)
);

CREATE TABLE IF NOT EXISTS agent_blueprints (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    config_id CHAR(36) NOT NULL,
    type ENUM('blueprint', 'mask') NOT NULL DEFAULT 'blueprint',
    description VARCHAR(255),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT agent_blueprint_pk PRIMARY KEY (id),
    CONSTRAINT agent_blueprint_workspace_config_id_uk UNIQUE (workspace_id, project_id, id)
);

CREATE TABLE IF NOT EXISTS agent_config_values (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    config_id CHAR(36) NOT NULL,
    `key` VARCHAR(255) NOT NULL,
    `value` TEXT NOT NULL,
    type ENUM('string', 'integer', 'float', 'boolean', 'prompt', 'prompt_commit') NOT NULL DEFAULT 'string',
    description TEXT,
    valid_from_blueprint_id CHAR(36) NOT NULL,
    valid_to_blueprint_id CHAR(36),
    CONSTRAINT agent_config_values_pk PRIMARY KEY (id),
    CONSTRAINT agent_config_values_workspace_blueprint_key_uk UNIQUE (workspace_id, project_id, valid_from_blueprint_id, valid_to_blueprint_id, `key`)
);

CREATE TABLE IF NOT EXISTS agent_config_envs (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    env_name VARCHAR(50) NOT NULL,
    config_id CHAR(36) NOT NULL,
    blueprint_id CHAR(36) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT agent_config_envs_pk PRIMARY KEY (id),
    CONSTRAINT agent_config_envs_workspace_config_env_uk UNIQUE (workspace_id, project_id, env_name)
);

--rollback DROP TABLE IF EXISTS agent_config_envs;
--rollback DROP TABLE IF EXISTS agent_config_values;
--rollback DROP TABLE IF EXISTS agent_blueprint;
--rollback DROP TABLE IF EXISTS agent_config;
