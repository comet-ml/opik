--liquibase formatted sql
--changeset borystkachenko:000072_add_environment_to_prompt_versions
--comment: Add environment to prompt_versions and enforce single-owner invariant per (workspace, prompt, environment)

ALTER TABLE prompt_versions ADD COLUMN environment VARCHAR(150) NULL;

-- Enforce single-owner-per-environment per prompt; supports env-mapped version lookup on GET /prompts/{id}?environment=... and PATCH /versions/{id}.
CREATE UNIQUE INDEX idx_prompt_versions_workspace_prompt_environment
    ON prompt_versions (workspace_id, prompt_id, environment);

--rollback DROP INDEX idx_prompt_versions_workspace_prompt_environment ON prompt_versions;
--rollback ALTER TABLE prompt_versions DROP COLUMN environment;

