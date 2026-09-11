--liquibase formatted sql
--changeset boryst:000109_add_cache_creation_split_to_cipx_spends
--comment: Split cache-creation tokens by TTL on cipx_spends (5m vs 1h, OPIK-7392)

-- Split the cache-creation token count by TTL. The proxy emits the split on
-- cipx.call.usage.cache_creation.{ephemeral_5m_input_tokens, ephemeral_1h_input_tokens}; it was
-- collapsed into the single u_cache_creation lump here. u_cache_creation stays the lump total;
-- existing rows read the default 0 for the new columns.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS u_cache_creation_5m Int64 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS u_cache_creation_1h Int64 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_spends ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS u_cache_creation_5m, DROP COLUMN IF EXISTS u_cache_creation_1h;

