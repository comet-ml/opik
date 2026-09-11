--liquibase formatted sql
--changeset borystkachenko:000055_alter_agent_config_envs_for_history
--comment: Convert agent_config_envs to history table for deployment tracking

ALTER TABLE agent_config_envs DROP COLUMN last_updated_by;
ALTER TABLE agent_config_envs DROP COLUMN last_updated_at;
ALTER TABLE agent_config_envs DROP INDEX agent_config_envs_workspace_config_env_uk;
ALTER TABLE agent_config_envs ADD COLUMN ended_at TIMESTAMP(6) NULL DEFAULT NULL;
CREATE INDEX idx_env_history_lookup ON agent_config_envs (workspace_id, project_id, env_name, ended_at);

--rollback DROP INDEX idx_env_history_lookup ON agent_config_envs;
--rollback ALTER TABLE agent_config_envs DROP COLUMN ended_at;
--rollback ALTER TABLE agent_config_envs ADD CONSTRAINT agent_config_envs_workspace_config_env_uk UNIQUE (workspace_id, project_id, env_name);
--rollback ALTER TABLE agent_config_envs ADD COLUMN last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);
--rollback ALTER TABLE agent_config_envs ADD COLUMN last_updated_by VARCHAR(100) NOT NULL;
