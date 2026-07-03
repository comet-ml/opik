--liquibase formatted sql
--changeset andriid:000095_add_session_id_to_cipx_trace_identity
--comment: Add session_id (cipx Claude Code session id) to cipx_trace_identities for the personal Sessions metric

-- cipx emits metadata.cipx.session.session_id per trace (one session -> many traces/turns) and
-- mirrors it onto the opik trace's native thread_id. Extracted here from the same cipx.session
-- metadata as the other identity columns; distinct session_id per user is the session count.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS session_id String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS session_id;
