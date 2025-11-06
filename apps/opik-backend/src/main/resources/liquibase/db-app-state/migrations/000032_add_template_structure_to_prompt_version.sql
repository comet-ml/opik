--liquibase formatted sql
--changeset aliaksandrk:add_template_structure_to_prompt_version

ALTER TABLE prompt_versions ADD COLUMN template_structure ENUM('string', 'chat') NOT NULL DEFAULT 'string';

--rollback ALTER TABLE prompt_versions DROP COLUMN template_structure;

