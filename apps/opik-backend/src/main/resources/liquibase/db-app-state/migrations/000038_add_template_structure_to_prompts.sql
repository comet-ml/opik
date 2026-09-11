--liquibase formatted sql
--changeset alexkuzmik:000038_add_template_structure_to_prompts
--comment: Add template_structure column to prompts table

ALTER TABLE prompts ADD COLUMN template_structure ENUM('text', 'chat') NOT NULL DEFAULT 'text';

--rollback ALTER TABLE prompts DROP COLUMN template_structure;

