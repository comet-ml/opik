--liquibase formatted sql
--changeset BorisTkachenko:000008_add_name_to_provider_api_key

ALTER TABLE llm_provider_api_key ADD COLUMN name VARCHAR(150) DEFAULT NULL;

--rollback ALTER TABLE llm_provider_api_key DROP COLUMN name;
