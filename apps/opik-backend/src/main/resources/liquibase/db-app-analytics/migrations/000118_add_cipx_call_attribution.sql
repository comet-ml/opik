--liquibase formatted sql
--changeset aadereiko:000118_add_cipx_call_attribution
--comment: Persist cipx sub-agent attribution: per-call trigger/detail/turn/parent on cipx_spends, and the session-grain link rollup on cipx_trace_identities
--
-- Additive columns with defaults, so a backend that predates this changeset never
-- names them and one that postdates it reads '' / 0 on pre-existing rows.
--
-- cipx_spends, per LLM call:
--
--   trigger              which loop the call came from: user_turn | tool_continuation
--                        | subagent | automated | unknown. Closed enum ->
--                        LowCardinality.
--
--   trigger_detail       overloaded on purpose: the agent NAME when
--                        trigger='subagent', the tool name on a
--                        tool_continuation. String, not LowCardinality: it holds
--                        MCP tool names and user-authored agent names, so the
--                        value space is open rather than an enum. The sibling
--                        cipx_spend_blocks.tool_name is String for the same
--                        reason, while the genuinely closed category columns stay
--                        LowCardinality.
--
--   turn_key             groups every call of one user turn, so "what did that one
--                        prompt cost, including the agents it spawned" is a query.
--
--   parent_tool_use_id   the parent's `Agent` tool_use id — the identity of this
--                        agent INVOCATION, stable across every call the run makes.
--                        Group by it for per-agent cost; it is also what separates
--                        two concurrent agents of the same type.
--
--   link_failure_reason  why LINK declined, when it did: no_dispatch_captured |
--                        ambiguous_prompt | replay_path | parent_unresolved. Empty
--                        when the call linked, but empty is NOT a census of
--                        success — a call the shape check never recognised as an
--                        agent carries no reason even when it was refused.
--
--                        Count ambiguous_prompt WITHOUT a trigger filter or you
--                        miss most of it: it is stamped on whatever trigger the
--                        refused call ships as, because the agent types that carry
--                        the Agent tool ship their refused calls as
--                        tool_continuation. Every other value sits on
--                        trigger='subagent' already.
--
-- cipx_trace_identities, per session (re-stamped on every trace upsert):
--
--   agents_dispatched    subagent dispatches OBSERVED. Deduplicated by tool_use id.
--   agents_linked        of those, how many a later call tied back to its dispatch.
--   agents_ambiguous     of those, how many cipx declined to attribute because two
--                        dispatches shared a byte-identical prompt. Fail-closed on
--                        purpose, NOT a defect — without this third number,
--                        "we failed" and "we correctly refused" collapse into one.
--   cipx_version         daemon build that wrote the envelope, so "link rate
--                        collapsed" and "half the fleet predates the fix" are not
--                        the same rows.
--
-- Reading the counters — all four rules are load-bearing:
--
--   1. Aggregate with max() per session_id, NEVER sum(). They are session running
--      totals re-stamped on every trace upsert, so a session with N traces holds N
--      successive snapshots and sum() multiplies by the trace count.
--   2. Clamp missed = dispatched - ambiguous - linked at zero. The producer's
--      dispatch log is capped per session and evicts oldest-first, so past the cap
--      a dispatch counted ambiguous can later link and missed can go negative.
--   3. rate = linked / (dispatched - ambiguous) is UNDEFINED, not zero, at a zero
--      denominator. Reporting 0% invents a failure out of an idle session.
--   4. Zero is not "unknown". Absence reads as zero, which is faithful for a
--      daemon that dispatched nothing; one too old to emit them is told apart by
--      cipx_version / schema_version.
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

-- Roll the backend back BEFORE this: CipxSpendDAO and CipxTraceIdentityDAO each
-- name their new columns in one INSERT, so dropping them fails every cipx write as
-- a whole batch, and the ingestion listener logs-and-swallows that with no retry
-- and no dead letter.
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS agents_dispatched, DROP COLUMN IF EXISTS agents_linked, DROP COLUMN IF EXISTS agents_ambiguous, DROP COLUMN IF EXISTS cipx_version;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `trigger`, DROP COLUMN IF EXISTS trigger_detail, DROP COLUMN IF EXISTS turn_key, DROP COLUMN IF EXISTS parent_tool_use_id, DROP COLUMN IF EXISTS link_failure_reason;

