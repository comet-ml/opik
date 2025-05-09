--liquibase formatted sql
--changeset thiagohora:000016_add_base_url_and_headers_to_ll_provider_api_key

ALTER TABLE llm_provider_api_key
    ADD COLUMN headers JSON DEFAULT NULL,
    ADD COLUMN base_url VARCHAR(255) DEFAULT NULL;

--rollback ALTER TABLE llm_provider_api_key DROP COLUMN headers;
--rollback ALTER TABLE llm_provider_api_key DROP COLUMN base_url;
