--liquibase formatted sql
--changeset boryst:000121_add_aiu_nano_to_cipx_tables
--comment: Add the per-call provider usage units to cipx_spends and cipx_spend_blocks

-- NULL when the span reports no usage units; per block, the block's share of the span's total.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS aiu_nano Nullable(Int64);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS aiu_nano Nullable(Float64);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS aiu_nano;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spend_blocks ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS aiu_nano;
