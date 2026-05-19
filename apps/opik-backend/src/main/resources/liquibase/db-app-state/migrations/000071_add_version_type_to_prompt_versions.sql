--liquibase formatted sql
--changeset borystkachenko:000071_add_version_type_to_prompt_versions
--comment: Add version_type discriminator to prompt_versions to support mask overlays alongside regular prompt versions

ALTER TABLE prompt_versions ADD COLUMN version_type ENUM('prompt_version', 'mask') NOT NULL DEFAULT 'prompt_version';

--rollback ALTER TABLE prompt_versions DROP COLUMN version_type;
