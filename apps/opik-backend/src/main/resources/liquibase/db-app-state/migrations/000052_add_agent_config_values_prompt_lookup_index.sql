--liquibase formatted sql
--changeset borystkachenko:000052_add_agent_config_values_prompt_lookup_index
--comment: Add index to agent_config_values for efficient prompt reference lookup by workspace, type, and value

CREATE INDEX idx_acv_workspace_type_value
    ON agent_config_values (workspace_id, type, `value`(150));

--rollback DROP INDEX idx_acv_workspace_type_value ON agent_config_values;
