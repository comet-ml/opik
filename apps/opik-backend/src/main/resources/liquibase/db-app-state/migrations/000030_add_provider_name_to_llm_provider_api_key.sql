--liquibase formatted sql
--changeset idoberko2:000030_add_provider_name_to_llm_provider_api_key
--comment: Add provider_name column to support multiple custom LLM providers per workspace

-- Add provider_name column with '__NULL__' as default (sentinel value for NULL)
-- This allows the unique constraint to work properly with NULL values
-- Size (150) matches the name column for consistency
ALTER TABLE llm_provider_api_key 
    ADD COLUMN provider_name VARCHAR(150) NOT NULL DEFAULT '__NULL__';

-- Add new unique constraint on (workspace_id, provider, provider_name)
-- This must be created BEFORE dropping the old constraint to avoid data slipping through
ALTER TABLE llm_provider_api_key
    ADD CONSTRAINT llm_provider_api_key_workspace_provider_name 
    UNIQUE (workspace_id, provider, provider_name);

-- Drop old unique constraint after the new one is in place
ALTER TABLE llm_provider_api_key 
    DROP INDEX llm_provider_api_key_workspace_id_provider;

--rollback ALTER TABLE llm_provider_api_key ADD CONSTRAINT llm_provider_api_key_workspace_id_provider UNIQUE (workspace_id, provider);
--rollback ALTER TABLE llm_provider_api_key DROP CONSTRAINT llm_provider_api_key_workspace_provider_name;
--rollback ALTER TABLE llm_provider_api_key DROP COLUMN provider_name;

