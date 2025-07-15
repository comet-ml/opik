--liquibase formatted sql
--changeset idoberko2:000020_nullable_api_key

ALTER TABLE llm_provider_api_key MODIFY COLUMN api_key TEXT NULL;

--rollback empty
