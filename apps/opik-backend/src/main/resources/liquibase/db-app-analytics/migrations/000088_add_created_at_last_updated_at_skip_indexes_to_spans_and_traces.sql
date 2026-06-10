--liquibase formatted sql
--changeset thiagohora:000088_add_created_at_last_updated_at_skip_indexes_to_spans_and_traces
--comment: OPIK-6849 - Add minmax skip indexes on created_at / last_updated_at for spans and traces to speed up range filters (>=, <, etc.). Both columns correlate with insertion order, so minmax granule pruning is effective. Indexes are added and materialized so existing parts are covered too (MATERIALIZE INDEX submits an async background mutation - monitor via SELECT * FROM system.mutations WHERE is_done = 0 AND table IN ('traces','spans')).

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_spans_created_at created_at TYPE minmax GRANULARITY 1,
    ADD INDEX IF NOT EXISTS idx_spans_last_updated_at last_updated_at TYPE minmax GRANULARITY 1,
    MATERIALIZE INDEX idx_spans_created_at,
    MATERIALIZE INDEX idx_spans_last_updated_at;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_traces_created_at created_at TYPE minmax GRANULARITY 1,
    ADD INDEX IF NOT EXISTS idx_traces_last_updated_at last_updated_at TYPE minmax GRANULARITY 1,
    MATERIALIZE INDEX idx_traces_created_at,
    MATERIALIZE INDEX idx_traces_last_updated_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_spans_created_at, DROP INDEX IF EXISTS idx_spans_last_updated_at;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_traces_created_at, DROP INDEX IF EXISTS idx_traces_last_updated_at;
