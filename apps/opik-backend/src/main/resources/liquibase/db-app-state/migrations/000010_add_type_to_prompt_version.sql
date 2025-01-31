--liquibase formatted sql
--changeset BorisTkachenko:add_type_to_prompt_version

ALTER TABLE prompt_versions ADD COLUMN type ENUM('mustache', 'jinja2') NOT NULL DEFAULT 'mustache';

--rollback ALTER TABLE prompt_versions DROP COLUMN type;
