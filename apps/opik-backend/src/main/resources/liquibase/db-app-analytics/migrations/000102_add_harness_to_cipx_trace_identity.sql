--liquibase formatted sql
--changeset andriid:000102_add_harness_to_cipx_trace_identity
--comment: Add harness (cipx coding harness: claude_code / codex / ...) to cipx_trace_identities for the composition harness node

-- cipx stamps metadata.cipx.session.harness per trace ("claude_code", "codex", ...); the proxy sets it
-- from the request's provider. Extracted here from the same cipx.session metadata as the other identity
-- columns so the AI-spend composition can group spend by harness instead of assuming Claude Code. Legacy
-- traces predating harness tagging carry '' (read side coalesces '' -> claude_code).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS harness LowCardinality(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS harness;

