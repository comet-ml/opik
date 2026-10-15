--liquibase formatted sql
--changeset petrot:000133_add_agent_context_to_cipx_trace_identities
--comment: Repository skill trees and instruction-file blobs per cipx trace, for the teaches-agent metric

-- Parallel arrays per group (lengths must match); scope 'repo' = committed at HEAD, 'user'/'plugin' may reuse them; version_id = git tree (skills) / blob (files) id.
-- Status '' = not sent (older cipx), 'listed' = sent, 'truncated' = over 256 entries, stored empty; 'failed' may follow.
-- Rows written before these columns existed read empty arrays and ''.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `skills.scope`                 Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `skills.root`                  Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `skills.name`                  Array(String),
    ADD COLUMN IF NOT EXISTS `skills.version_id`            Array(String),
    ADD COLUMN IF NOT EXISTS skills_status                  LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS `instruction_files.scope`      Array(LowCardinality(String)),
    ADD COLUMN IF NOT EXISTS `instruction_files.path`       Array(String),
    ADD COLUMN IF NOT EXISTS `instruction_files.version_id` Array(String),
    ADD COLUMN IF NOT EXISTS instruction_files_status       LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `skills.scope`, DROP COLUMN IF EXISTS `skills.root`, DROP COLUMN IF EXISTS `skills.name`, DROP COLUMN IF EXISTS `skills.version_id`, DROP COLUMN IF EXISTS skills_status, DROP COLUMN IF EXISTS `instruction_files.scope`, DROP COLUMN IF EXISTS `instruction_files.path`, DROP COLUMN IF EXISTS `instruction_files.version_id`, DROP COLUMN IF EXISTS instruction_files_status;
