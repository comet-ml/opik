--liquibase formatted sql
--changeset idoberko2:000025_add_provider_name_to_llm_provider_api_key
--comment: Add provider_name column to support multiple custom LLM providers per workspace

-- Add provider_name column (nullable for backward compatibility)
ALTER TABLE llm_provider_api_key 
    ADD COLUMN provider_name VARCHAR(100) DEFAULT NULL;

-- Drop old unique constraint
ALTER TABLE llm_provider_api_key 
    DROP INDEX llm_provider_api_key_workspace_id_provider;

-- Add new compound unique constraint
-- For non-custom providers: (workspace_id, provider) must be unique (provider_name will be NULL)
-- For custom providers: (workspace_id, provider, provider_name) must be unique
ALTER TABLE llm_provider_api_key 
    ADD CONSTRAINT llm_provider_api_key_workspace_provider_name 
    UNIQUE (workspace_id, provider, provider_name);

--rollback ALTER TABLE llm_provider_api_key DROP CONSTRAINT llm_provider_api_key_workspace_provider_name;
--rollback ALTER TABLE llm_provider_api_key ADD CONSTRAINT llm_provider_api_key_workspace_id_provider UNIQUE (workspace_id, provider);
--rollback ALTER TABLE llm_provider_api_key DROP COLUMN provider_name;

