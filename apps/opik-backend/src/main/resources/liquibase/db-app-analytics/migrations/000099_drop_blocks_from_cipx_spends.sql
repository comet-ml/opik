--liquibase formatted sql
--changeset boryst:000099_drop_blocks_from_cipx_spends
--comment: Drop cipx_spends.blocks; block rows move to the dedicated cipx_spend_blocks table

-- The blocks Array(Tuple) held ~99% of the row weight and forced every composition/breakdown
-- query to ARRAY JOIN it and recompute per-span allocation with window functions. Blocks now
-- land in cipx_spend_blocks (one row per block, allocation precomputed at ingestion), so
-- cipx_spends keeps only the span-level call data (model + usage counters).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS blocks;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS blocks Array(Tuple(category String, side String, cache_status String, parent_category String, chars Int64, tool_name String, tool_server String, tool_use_id String, resource String, kind String));
