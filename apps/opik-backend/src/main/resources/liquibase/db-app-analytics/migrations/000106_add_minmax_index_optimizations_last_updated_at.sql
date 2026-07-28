--liquibase formatted sql
--changeset awkoy:000106_add_minmax_index_optimizations_last_updated_at
--comment: Add minmax skip index on optimizations.last_updated_at so the stalled-run reaper's lookback-floored scan prunes granules instead of re-reading the whole table every cycle (OPIK-7159)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_optimizations_last_updated_at last_updated_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_optimizations_last_updated_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_optimizations_last_updated_at;
