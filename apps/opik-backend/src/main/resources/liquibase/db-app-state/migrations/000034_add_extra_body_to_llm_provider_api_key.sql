--liquibase formatted sql
--changeset yariv:000034_add_extra_body_to_llm_provider_api_key
--comment: Add extra_body column to support custom provider-specific request parameters

ALTER TABLE llm_provider_api_key ADD COLUMN extra_body JSON DEFAULT NULL;

--rollback ALTER TABLE llm_provider_api_key DROP COLUMN extra_body;


