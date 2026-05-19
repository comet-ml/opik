--liquibase formatted sql
--changeset borystkachenko:000073_add_version_number_to_prompt_versions
--comment: Add sequential version_number (v<N>) to prompt_versions and enforce uniqueness per (workspace, prompt, version_number). Masks keep version_number NULL; MySQL allows multiple NULLs in a unique index.

ALTER TABLE prompt_versions ADD COLUMN version_number VARCHAR(10) NULL;

CREATE UNIQUE INDEX idx_prompt_versions_workspace_prompt_version_number
    ON prompt_versions (workspace_id, prompt_id, version_number);

--rollback DROP INDEX idx_prompt_versions_workspace_prompt_version_number ON prompt_versions;
--rollback ALTER TABLE prompt_versions DROP COLUMN version_number;
