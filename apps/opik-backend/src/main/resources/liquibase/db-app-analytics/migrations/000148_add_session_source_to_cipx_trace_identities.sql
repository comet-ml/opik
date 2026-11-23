--liquibase formatted sql
--changeset petrot:000148_add_session_source_to_cipx_trace_identities
--comment: Claude Code SessionStart source per cipx session (startup/resume/fork/clear/compact)

-- '' when cipx sent no source; rows written before this column existed read ''.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS session_source LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS session_source;
