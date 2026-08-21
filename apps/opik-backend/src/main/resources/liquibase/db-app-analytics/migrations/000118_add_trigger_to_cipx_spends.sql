--liquibase formatted sql
--changeset aadereiko:000118_add_trigger_to_cipx_spends
--comment: Persist the per-call trigger, its detail (subagent NAME), the turn key, the parent tool_use id and the link failure reason on cipx_spends
--
-- cipx already ships all five on metadata.cipx.call; none of them reached a typed column, so the
-- spend tables cannot tell a subagent's spend from the main agent's, let alone name the agent or
-- reconstruct the parent/child tree the AI Spend UI wants.
--
--   trigger             what caused this call: user_turn | tool_continuation | subagent | automated
--                       | unknown. Closed enum -> LowCardinality. Note TWO near-synonymous buckets
--                       in a GROUP BY trigger: the literal 'unknown' is cipx positively declining to
--                       classify a call it saw (wire.TriggerUnknown, an operator-visible health
--                       metric), while '' is a row written before this column existed and was never
--                       classified at all. Readers must not fold them together — the first is a
--                       classifier miss to chase, the second is history.
--   trigger_detail      trigger-dependent qualifier. For trigger='subagent' this is the agent NAME
--                       (the parent's `Agent` tool_use input.subagent_type: "code-reviewer",
--                       "Explore", ...); for trigger='tool_continuation' it is the tool name; for
--                       trigger='automated' it can be the literal 'auto_classifier', which is a call
--                       KIND and not an agent -- a per-agent rollup must scope itself to
--                       trigger='subagent' rather than grouping this column on its own. Empty when
--                       the proxy could not resolve it -- deliberately NOT defaulted, so an empty
--                       string means "unknown", never "general-purpose".
--                       Plain String, not LowCardinality: the value space is open (MCP tool names
--                       plus user-authored agent names from .claude/agents), matching the sibling
--                       cipx_spend_blocks.tool_name which is String for the same reason. This table
--                       already reserves LowCardinality for genuinely closed enums (category,
--                       subcategory, parent_category, trigger); a dictionary over an unbounded name
--                       space stops paying for itself past low thousands of distinct values, and
--                       narrowing it later is an ALTER MODIFY COLUMN that rewrites the column.
--   turn_key            groups a user prompt's root call with every continuation that followed it
--                       (SHA256 hex of the prompt text). The grain a per-turn read aggregates on.
--   parent_tool_use_id  the `Agent` tool_use that spawned this call -- the identity of one agent
--                       INVOCATION, stable across every call that agent makes. This is the edge of
--                       the agent tree: child rows point at the parent's tool_use id.
--   link_failure_reason why LINK did not fully resolve this call's parent: no_dispatch_captured |
--                       ambiguous_prompt | replay_path | parent_unresolved. Closed enum ->
--                       LowCardinality. Stamped only on calls that shipped as trigger='subagent', so
--                       '' on any other trigger means "nothing to report", not "resolved".
--                       This column is what makes an unattributed subagent triageable: it separates
--                       cipx correctly FAIL-CLOSING on two byte-identical peer dispatches
--                       (ambiguous_prompt -- working as designed, do not chase) from cipx LOSING a
--                       dispatch (no_dispatch_captured -- a real defect). Without it both collapse
--                       into one indistinguishable "(unattributed)" bucket downstream.
--                       Caveat carried from the producer: it is NOT a complete failure census. A
--                       subagent whose dispatch was never observed is unidentifiable as a subagent
--                       and ships with no reason at all; that population is only visible in the
--                       session-grain counters on cipx_trace_identities (migration 000119).
--                       parent_unresolved is the one value that can appear on a call that DID link
--                       (spend attributed correctly, trace tree shape wrong) -- an attribution audit
--                       should ignore it, a tree-shape audit should not.
--
-- Additive columns with defaults; every pre-existing row reads '' (unknown), which is honest --
-- those rows were written before the proxy carried the fields.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `trigger`           LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS trigger_detail      String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS turn_key            String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS parent_tool_use_id  String                 DEFAULT '',
    ADD COLUMN IF NOT EXISTS link_failure_reason LowCardinality(String) DEFAULT '';

-- Rollback is NOT safe to run while a backend carrying these columns is live: CipxSpendDAO names all
-- five in a single INSERT, so dropping them makes every cipx spend insert fail as a whole batch, and
-- CostIntelligenceIngestionListener logs-and-swallows that error with no retry and no dead letter --
-- the spend is lost silently, not deferred. Roll the backend back first, then the schema.
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `trigger`, DROP COLUMN IF EXISTS trigger_detail, DROP COLUMN IF EXISTS turn_key, DROP COLUMN IF EXISTS parent_tool_use_id, DROP COLUMN IF EXISTS link_failure_reason;
