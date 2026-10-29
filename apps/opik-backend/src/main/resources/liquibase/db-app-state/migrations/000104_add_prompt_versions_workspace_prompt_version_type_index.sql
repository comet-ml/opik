--liquibase formatted sql
--changeset thiagohora:000104_add_prompt_versions_workspace_prompt_version_type_index
--comment: Index prompt_versions by (workspace_id, prompt_id, version_type) so a prompt's latest version and version count stop reading every version row

-- Every "latest version" lookup is WHERE workspace_id = ? AND prompt_id = ? AND version_type = 'prompt_version'
-- ORDER BY id DESC LIMIT 1, and version_count is the same filter under COUNT. No existing index holds version_type,
-- so both read the full row of every version: on a prompt with 109k versions, GET /prompts/{id} measured 2.9s.
-- With version_type in the index the latest version is a single index dive and the count is read from the index.
--
-- id is deliberately not listed: InnoDB appends the primary key to every secondary index, so ORDER BY id DESC
-- is served by this index without it.
CREATE INDEX idx_prompt_versions_workspace_prompt_version_type
    ON prompt_versions (workspace_id, prompt_id, version_type);

--rollback DROP INDEX idx_prompt_versions_workspace_prompt_version_type ON prompt_versions;
