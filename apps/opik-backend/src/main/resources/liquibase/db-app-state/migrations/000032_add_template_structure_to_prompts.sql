--liquibase formatted sql
--changeset aliaksandrk:add_template_structure_to_prompts

ALTER TABLE prompts ADD COLUMN template_structure ENUM('string', 'chat') NOT NULL DEFAULT 'string';

--rollback ALTER TABLE prompts DROP COLUMN template_structure;

