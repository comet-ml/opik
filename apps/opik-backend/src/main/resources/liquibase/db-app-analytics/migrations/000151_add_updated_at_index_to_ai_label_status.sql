--liquibase formatted sql
--changeset yaroslavb:000151_add_updated_at_index_to_ai_label_status
--comment: Add a minmax index on ai_label_status.updated_at for the topic vectors window read (OPIK-8981)

-- The topic vectors window read takes a workspace's statuses written since the window's lower bound
-- (updated_at >= the bound), before the dedup, so a granule written only before the bound is skipped. updated_at is
-- set by ClickHouse at insert and never changes for a row, so a minmax index on it is safe before the dedup.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.ai_label_status ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_updated_at updated_at TYPE minmax GRANULARITY 1;

-- Materialize the index on existing data parts (runs as an async mutation in ClickHouse)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.ai_label_status ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_updated_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.ai_label_status ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_updated_at;
