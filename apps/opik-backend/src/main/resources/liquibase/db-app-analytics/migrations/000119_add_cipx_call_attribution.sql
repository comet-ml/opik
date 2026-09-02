--liquibase formatted sql
--changeset aadereiko:000119_add_cipx_call_attribution
--comment: Add cipx call attribution columns

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `trigger`             LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS trigger_detail        String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS turn_key              String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS parent_tool_use_id    String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS link_failure_reason   LowCardinality(String) DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS agents_dispatched UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_linked     UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS agents_ambiguous  UInt32                 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cipx_version      LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS agents_dispatched, DROP COLUMN IF EXISTS agents_linked, DROP COLUMN IF EXISTS agents_ambiguous, DROP COLUMN IF EXISTS cipx_version;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `trigger`, DROP COLUMN IF EXISTS trigger_detail, DROP COLUMN IF EXISTS turn_key, DROP COLUMN IF EXISTS parent_tool_use_id, DROP COLUMN IF EXISTS link_failure_reason;

