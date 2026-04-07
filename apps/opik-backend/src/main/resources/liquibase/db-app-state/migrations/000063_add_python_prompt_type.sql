--liquibase formatted sql
--changeset itamargolan:add_python_prompt_type

ALTER TABLE prompt_versions MODIFY COLUMN type ENUM('mustache', 'jinja2', 'python') NOT NULL DEFAULT 'mustache';

--rollback ALTER TABLE prompt_versions MODIFY COLUMN type ENUM('mustache', 'jinja2') NOT NULL DEFAULT 'mustache';
