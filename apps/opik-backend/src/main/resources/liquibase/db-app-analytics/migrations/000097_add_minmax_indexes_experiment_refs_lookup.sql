--liquibase formatted sql
--changeset thiagohora:000097_add_minmax_index_experiment_items_trace_id
--comment: Add minmax skip index on experiment_items.trace_id so experiment-refs lookups by trace_id prune granules instead of a generic-exclusion scan (OPIK-7230)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_experiment_items_trace_id trace_id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_experiment_items_trace_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_items_trace_id;

--changeset thiagohora:000097_add_minmax_index_spans_id
--comment: Add minmax skip index on spans.id so span-id lookups prune granules instead of a generic-exclusion scan over the workspace (OPIK-7230)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_spans_id id TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_spans_id;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_spans_id;

