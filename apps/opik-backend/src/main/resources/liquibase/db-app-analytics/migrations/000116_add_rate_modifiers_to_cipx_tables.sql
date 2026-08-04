--liquibase formatted sql
--changeset aadereiko:000116_add_rate_modifiers_to_cipx_tables
--comment: Add the two per-call rate modifiers to cipx_spends and cipx_spend_blocks (fast mode + inference geography)

-- speed and inference_geo ride at the top level of the request body, next to model, and unlike the
-- other config knobs they change the *rate* rather than the token count:
--   speed = 'fast'      prices Opus 5 / 4.8 at $10/$50 per MTok instead of $5/$25
--   inference_geo = 'us' applies a 1.1x multiplier to every token category on Claude 4.6+
-- The two stack multiplicatively, and prompt-cache multipliers stack on top of both. Both must be
-- per-call: when the fast rate-limit pool or the account's usage credits run dry, Claude Code
-- silently falls back to standard speed mid-session, so a single session mixes fast- and
-- standard-priced calls. Additive columns with defaults; existing rows read the default (empty),
-- which prices as standard speed / global routing — the correct reading for pre-fast-mode history.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed         LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS inference_geo LowCardinality(String) DEFAULT '';

-- The per-user lane breakdowns (skills, servers, users) price from cipx_spend_blocks, which
-- already denormalizes `model` off the same cipx.call node. The rate modifiers have to ride
-- along for the same reason model does: a block's cost is only computable with the rate that
-- produced it, and joining back to cipx_spends per block would defeat the denormalization.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed         LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS inference_geo LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed, DROP COLUMN IF EXISTS inference_geo;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed, DROP COLUMN IF EXISTS inference_geo;
