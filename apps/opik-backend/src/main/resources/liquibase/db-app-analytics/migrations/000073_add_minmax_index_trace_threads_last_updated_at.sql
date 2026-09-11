--liquibase formatted sql
--changeset daniela:000073_add_minmax_index_trace_threads_last_updated_at
--comment: Add minmax skip index on last_updated_at to enable time-bounded FINAL queries for the closing job (OPIK-5050)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_last_updated_at last_updated_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_last_updated_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_last_updated_at;
