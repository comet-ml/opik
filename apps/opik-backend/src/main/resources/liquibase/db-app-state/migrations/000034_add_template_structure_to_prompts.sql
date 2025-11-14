--liquibase formatted sql
--changeset aliaksandrk:add_template_structure_to_prompts

ALTER TABLE prompts ADD COLUMN template_structure ENUM('text', 'chat') NOT NULL DEFAULT 'text';

--rollback ALTER TABLE prompts DROP COLUMN template_structure;

