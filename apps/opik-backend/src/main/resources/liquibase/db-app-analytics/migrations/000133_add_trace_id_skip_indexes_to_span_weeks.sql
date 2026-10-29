--liquibase formatted sql
--changeset thiagohora:000133_add_trace_id_skip_indexes_to_span_weeks
--comment: OPIK-8706 - Add minmax and bloom_filter skip indexes on span_weeks.trace_id for the lookups without a project_id (dataset items, optimizations, and experiments, experiment items and dataset item versions without target projects). The (workspace_id, project_id, trace_id, id_week) sort key prunes those by workspace only; on prod-test the pair cut the granules read by a median 55%, up to 92% on small trace sets, for about 8% more storage. Added and materialized so existing parts are covered too (MATERIALIZE INDEX submits an async background mutation - monitor via SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'span_weeks').

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_span_weeks_trace_id_mm trace_id TYPE minmax GRANULARITY 1,
    ADD INDEX IF NOT EXISTS idx_span_weeks_trace_id_bf trace_id TYPE bloom_filter(0.01) GRANULARITY 1,
    MATERIALIZE INDEX idx_span_weeks_trace_id_mm,
    MATERIALIZE INDEX idx_span_weeks_trace_id_bf;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.span_weeks ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_span_weeks_trace_id_mm, DROP INDEX IF EXISTS idx_span_weeks_trace_id_bf;
