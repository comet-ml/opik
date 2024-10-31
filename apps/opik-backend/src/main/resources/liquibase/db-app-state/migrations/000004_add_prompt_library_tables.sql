--liquibase formatted sql
--changeset thiagohora:add_prompt_library_tables

CREATE TABLE IF NOT EXISTS prompts (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    workspace_id VARCHAR(150) NOT NULL,
    CONSTRAINT `prompts_pk` PRIMARY KEY (id),
    CONSTRAINT `prompts_workspace_id_name_uk` UNIQUE (workspace_id, name)
);

CREATE TABLE IF NOT EXISTS prompt_versions (
    id CHAR(36) NOT NULL,
    prompt_id CHAR(36) NOT NULL,
    commit VARCHAR(7) NOT NULL,
    template MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    workspace_id VARCHAR(150) NOT NULL,
    CONSTRAINT `prompt_versions_pk` PRIMARY KEY (id),
    CONSTRAINT `prompt_versions_prompt_id_version_uk` UNIQUE (workspace_id, prompt_id, commit)
);

--rollback DROP TABLE IF EXISTS prompt_versions;
--rollback DROP TABLE IF EXISTS prompts;
