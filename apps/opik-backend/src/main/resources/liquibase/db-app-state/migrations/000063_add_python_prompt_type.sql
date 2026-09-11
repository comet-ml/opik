--liquibase formatted sql
--changeset itamargolan:000063_add_python_prompt_type
--comment: Add python to prompt_versions type ENUM for Python-based evaluator prompts

ALTER TABLE prompt_versions MODIFY COLUMN type ENUM('mustache', 'jinja2', 'python') NOT NULL DEFAULT 'mustache';

--rollback ALTER TABLE prompt_versions MODIFY COLUMN type ENUM('mustache', 'jinja2') NOT NULL DEFAULT 'mustache';

