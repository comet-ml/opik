--liquibase formatted sql
--changeset thiagohora:000017_add_provider_config_to_ll_provider_api_key

ALTER TABLE llm_provider_api_key ADD COLUMN configuration JSON DEFAULT NULL,
    MODIFY COLUMN api_key TEXT NOT NULL;

--rollback ALTER TABLE llm_provider_api_key DROP COLUMN configuration;

