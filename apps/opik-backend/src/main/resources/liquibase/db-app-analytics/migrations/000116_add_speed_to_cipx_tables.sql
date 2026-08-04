--liquibase formatted sql
--changeset aadereiko:000116_add_speed_to_cipx_tables
--comment: Add the fast-mode rate modifier to cipx_spends and cipx_spend_blocks

-- Read from cipx.call.config (alongside effort / thinking_type / max_tokens), NOT from the top
-- level of cipx.call where model lives — see CipxSpendDAO.SpanRow.from. Claude Code sends it at
-- the top level of the *Anthropic* request body, next to that body's `model`, and the proxy
-- promotes it onto cipx.call.config with the rest of the per-request configuration; the two
-- "top levels" are different objects, so don't infer the wire path from where CC puts it.
--
-- Unlike the other config knobs it changes the *rate* rather than the token count:
-- speed = 'fast' prices Opus 5 / 4.8 at $10/$50 per MTok instead of $5/$25, and the prompt-cache
-- multipliers stack on top. It must be per-call: when the fast rate-limit pool or the account's
-- usage credits run dry, Claude Code silently falls back to standard speed mid-session, so a
-- single session mixes fast- and standard-priced calls. Additive column with a default; existing
-- rows read the default (empty), which prices as standard speed — the correct reading for
-- pre-fast-mode history.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

-- The per-user lane breakdowns (skills, servers, users) price from cipx_spend_blocks, which
-- already denormalizes `model` off the same cipx.call node. The rate modifier has to ride along
-- for the same reason model does: a block's cost is only computable with the rate that produced
-- it, and joining back to cipx_spends per block would defeat the denormalization.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
