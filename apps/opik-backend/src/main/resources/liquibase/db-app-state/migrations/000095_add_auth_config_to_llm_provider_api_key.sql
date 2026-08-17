--liquibase formatted sql
--changeset miguelg:000095_add_auth_config_to_llm_provider_api_key
--comment: Add encrypted auth_config column for dynamic token auth on custom providers

ALTER TABLE llm_provider_api_key ADD COLUMN auth_config TEXT DEFAULT NULL;

--rollback ALTER TABLE llm_provider_api_key DROP COLUMN auth_config;
