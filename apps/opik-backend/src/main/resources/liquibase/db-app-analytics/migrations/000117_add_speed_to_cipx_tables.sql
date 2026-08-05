--liquibase formatted sql
--changeset aadereiko:000117_add_speed_to_cipx_tables
--comment: Persist the per-call speed modifier on cipx_spends and cipx_spend_blocks
--
-- Mirrors cipx wire.CallConfig.Speed, parsed from cipx.call.config alongside effort /
-- thinking_type / max_tokens. It selects which rate table prices the call, and is per-call
-- rather than per-session: one session can carry more than one value.
-- '' means standard, including every row written before cipx emitted the field.
-- Denormalized onto blocks as well as spends, for the same reason `model` is: a block's cost
-- is only computable alongside the value that priced it.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
