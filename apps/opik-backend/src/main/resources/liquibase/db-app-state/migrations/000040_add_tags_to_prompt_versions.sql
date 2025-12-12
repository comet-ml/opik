--liquibase formatted sql
--changeset andrescrz:000040_add_tags_to_prompt_versions

ALTER TABLE prompt_versions
    ADD COLUMN tags MEDIUMTEXT DEFAULT NULL;

-- Avoids expensive: WHERE workspace_id = ? AND prompt_id = ? ORDER BY created_at DESC
CREATE INDEX idx_prompt_versions_workspace_id_prompt_id_created_at_desc
    ON prompt_versions (workspace_id, prompt_id, created_at DESC);

--rollback DROP INDEX idx_prompt_versions_workspace_id_prompt_id_created_at_desc ON prompt_versions;
--rollback ALTER TABLE prompt_versions DROP COLUMN tags;
