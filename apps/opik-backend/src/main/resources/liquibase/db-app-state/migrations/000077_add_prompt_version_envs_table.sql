--liquibase formatted sql
--changeset boryst:000077_add_prompt_version_envs_table

CREATE TABLE prompt_version_envs (
    id           CHAR(36)     NOT NULL,
    workspace_id VARCHAR(255) NOT NULL,
    prompt_id    CHAR(36)     NOT NULL,
    version_id   CHAR(36)     NOT NULL,
    environment  VARCHAR(150) NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   VARCHAR(255) NOT NULL DEFAULT '',
    ended_at     TIMESTAMP(6) NULL DEFAULT NULL,
    active_env   VARCHAR(150) GENERATED ALWAYS AS (IF(ended_at IS NULL, environment, NULL)) STORED,
    PRIMARY KEY (id),
    INDEX idx_pve_env_lookup (workspace_id, prompt_id, environment, ended_at),
    INDEX idx_pve_version (workspace_id, version_id, ended_at),
    UNIQUE INDEX uq_pve_active_env (workspace_id, prompt_id, active_env)
);

ALTER TABLE prompt_versions DROP INDEX idx_prompt_versions_workspace_prompt_environment;
ALTER TABLE prompt_versions DROP COLUMN environment;

--rollback DROP TABLE prompt_version_envs;
--rollback ALTER TABLE prompt_versions ADD COLUMN environment VARCHAR(150) NULL;
--rollback CREATE UNIQUE INDEX idx_prompt_versions_workspace_prompt_environment ON prompt_versions (workspace_id, prompt_id, environment);
