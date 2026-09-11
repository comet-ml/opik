--liquibase formatted sql
--changeset aadereiko:000117_add_speed_to_cipx_tables
--comment: Persist the per-call speed modifier on cipx_spends and cipx_spend_blocks
--
-- Selects which rate table prices a call. Per-call, not per-session: one session can carry
-- more than one value. '' means standard, including every row written before the field existed.
-- Carried on blocks as well as spends because block-level cost needs the value that priced it.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS speed LowCardinality(String) DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS speed;
