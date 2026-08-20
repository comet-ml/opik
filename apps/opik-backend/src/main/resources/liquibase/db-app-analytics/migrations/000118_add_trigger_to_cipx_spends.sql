--liquibase formatted sql
--changeset aadereiko:000118_add_trigger_to_cipx_spends
--comment: Persist the per-call trigger, its detail (subagent NAME), the turn key and the parent tool_use id on cipx_spends
--
-- cipx already ships all four on metadata.cipx.call; none of them reached a typed column, so the
-- spend tables cannot tell a subagent's spend from the main agent's, let alone name the agent or
-- reconstruct the parent/child tree the AI Spend UI wants.
--
--   trigger             what caused this call: user_turn | tool_continuation | subagent | automated
--                       | unknown. Closed enum -> LowCardinality.
--   trigger_detail      trigger-dependent qualifier. For trigger='subagent' this is the agent NAME
--                       (the parent's `Agent` tool_use input.subagent_type: "code-reviewer",
--                       "Explore", ...); for trigger='tool_continuation' it is the tool name. Empty
--                       when the proxy could not resolve it -- deliberately NOT defaulted, so an
--                       empty string means "unknown", never "general-purpose".
--   turn_key            groups a user prompt's root call with every continuation that followed it
--                       (SHA256 hex of the prompt text). The grain a per-turn read aggregates on.
--   parent_tool_use_id  the `Agent` tool_use that spawned this call -- the identity of one agent
--                       INVOCATION, stable across every call that agent makes. This is the edge of
--                       the agent tree: child rows point at the parent's tool_use id.
--
-- Additive columns with defaults; every pre-existing row reads '' (unknown), which is honest --
-- those rows were written before the proxy carried the fields.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `trigger`          LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS trigger_detail     LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS turn_key           String DEFAULT '',
    ADD COLUMN IF NOT EXISTS parent_tool_use_id String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `trigger`, DROP COLUMN IF EXISTS trigger_detail, DROP COLUMN IF EXISTS turn_key, DROP COLUMN IF EXISTS parent_tool_use_id;

