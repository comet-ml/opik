--liquibase formatted sql
--changeset aadereiko:000120_add_link_failure_reason_and_widen_trigger_detail
--comment: Add link_failure_reason to cipx_spends and widen trigger_detail to String
--
-- Two changes that 000118 should have carried. By the time they were identified,
-- 000118 had already been applied in developer environments, and editing an
-- already-applied changeset fails liquibase's checksum validation, which aborts
-- every migration after it. A new changeset is the only safe way to land them.
--
--   link_failure_reason  why LINK declined, when it did: no_dispatch_captured |
--                        ambiguous_prompt | replay_path | parent_unresolved. Closed
--                        enum -> LowCardinality. Empty when the call linked, and
--                        empty is NOT a census of success.
--
--                        Where each value appears differs. ambiguous_prompt sits
--                        on ANY trigger, because it is a positive observation
--                        (two agents dispatched under one prompt hash) and the
--                        agent types that carry the Agent tool ship their refused
--                        calls as trigger='tool_continuation'. So count it
--                        WITHOUT a trigger filter or you miss most of it. Every
--                        other value sits only on trigger='subagent' and is
--                        already scoped by construction.
--
--                        Either way a call the shape check never recognised as an
--                        agent carries no reason even when it was refused. Read
--                        this column as a diagnosis, never as a failure count.
--
--   trigger_detail       widened LowCardinality(String) -> String. It holds MCP
--                        tool names and user-authored agent names, so the value
--                        space is open, not an enum; the sibling
--                        cipx_spend_blocks.tool_name is already String while the
--                        genuinely closed category columns are LowCardinality.
--                        MODIFY COLUMN, not ADD COLUMN IF NOT EXISTS: the column
--                        already exists, so an ADD is silently a no-op and the type
--                        would never change.
--
-- MODIFY COLUMN on a LowCardinality -> String widening rewrites the column, so this
-- is not metadata-only like 000118 was. It is a mutation on cipx_spends and will
-- take time proportional to the table on a large deployment.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS link_failure_reason LowCardinality(String) DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    MODIFY COLUMN trigger_detail String;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' MODIFY COLUMN trigger_detail LowCardinality(String);
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS link_failure_reason;

