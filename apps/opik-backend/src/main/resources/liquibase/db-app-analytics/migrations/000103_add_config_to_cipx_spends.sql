--liquibase formatted sql
--changeset boryst:000103_add_config_to_cipx_spends
--comment: Add per-call model-configuration columns to cipx_spends (thinking level + settings, OPIK-7288)

-- Claude Code sends per-request configuration knobs (reasoning effort, thinking mode, token cap,
-- context-management strategy) in the request body; the proxy now promotes them onto cipx.call.config.
-- These shape cost and behavior, so cipx_spends carries them per call. Additive columns with defaults;
-- existing rows read the default (empty / 0).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS effort             LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS thinking_type       LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS max_tokens          Int64 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS context_management  LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS effort, DROP COLUMN IF EXISTS thinking_type, DROP COLUMN IF EXISTS max_tokens, DROP COLUMN IF EXISTS context_management;

