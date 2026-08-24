--liquibase formatted sql
--changeset aadereiko:000120_add_link_failure_reason_and_widen_trigger_detail
--comment: Add link_failure_reason to cipx_spends and widen trigger_detail to String
--
-- Two changes that 000118 should have carried. It was already applied in developer
-- environments by the time they were identified, and editing an applied changeset
-- fails liquibase's checksum validation and aborts every migration after it.
--
--   link_failure_reason  why LINK declined: no_dispatch_captured | ambiguous_prompt
--                        | replay_path | parent_unresolved. Empty when the call
--                        linked, but empty is NOT a census of success -- a call the
--                        shape check never recognised as an agent carries no reason
--                        even when it was refused. Read it as a diagnosis.
--
--                        Count ambiguous_prompt WITHOUT a trigger filter or you miss
--                        most of it: it is stamped on any trigger, because the agent
--                        types carrying the Agent tool ship refused calls as
--                        tool_continuation. Every other value is on
--                        trigger='subagent' already.
--
--   trigger_detail       widened LowCardinality(String) -> String: it holds MCP tool
--                        names and user-authored agent names, so the value space is
--                        open. MODIFY, not ADD COLUMN IF NOT EXISTS -- the column
--                        exists, so an ADD is silently a no-op and the type would
--                        never change.
--
-- The widening rewrites the column, so unlike 000118 this is not metadata-only and
-- takes time proportional to the table.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS link_failure_reason LowCardinality(String) DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    MODIFY COLUMN trigger_detail String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' MODIFY COLUMN trigger_detail LowCardinality(String);
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS link_failure_reason;

