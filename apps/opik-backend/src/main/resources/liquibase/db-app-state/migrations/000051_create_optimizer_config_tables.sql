--liquibase formatted sql
--changeset borystkachenko:000051_create_optimizer_config_tables
--comment: Create optimizer configuration tables

CREATE TABLE IF NOT EXISTS optimizer_config (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT optimizer_config_pk PRIMARY KEY (id),
    CONSTRAINT optimizer_config_workspace_id_uk UNIQUE (workspace_id, project_id)
);

CREATE TABLE IF NOT EXISTS optimizer_blueprint (
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
    CONSTRAINT optimizer_blueprint_pk PRIMARY KEY (id),
    CONSTRAINT optimizer_blueprint_workspace_config_id_uk UNIQUE (workspace_id, project_id, id)
);

CREATE TABLE IF NOT EXISTS optimizer_config_values (
    id CHAR(36) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    project_id CHAR(36) NOT NULL,
    config_id CHAR(36) NOT NULL,
    `key` VARCHAR(255) NOT NULL,
    `value` VARCHAR(255) NOT NULL,
    type ENUM('string', 'number', 'prompt', 'promptversion') NOT NULL DEFAULT 'string',
    valid_from_blueprint_id CHAR(36) NOT NULL,
    valid_to_blueprint_id CHAR(36),
    CONSTRAINT optimizer_config_values_pk PRIMARY KEY (id),
    CONSTRAINT optimizer_config_values_workspace_blueprint_key_uk UNIQUE (workspace_id, project_id, valid_from_blueprint_id, `key`)
);

CREATE TABLE IF NOT EXISTS optimizer_config_envs (
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
    CONSTRAINT optimizer_config_envs_pk PRIMARY KEY (id),
    CONSTRAINT optimizer_config_envs_workspace_config_env_uk UNIQUE (workspace_id, project_id, env_name)
);

--rollback DROP TABLE IF EXISTS optimizer_config_envs;
--rollback DROP TABLE IF EXISTS optimizer_config_values;
--rollback DROP TABLE IF EXISTS optimizer_blueprint;
--rollback DROP TABLE IF EXISTS optimizer_config;
