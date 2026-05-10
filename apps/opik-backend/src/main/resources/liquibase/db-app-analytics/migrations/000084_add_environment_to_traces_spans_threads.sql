--liquibase formatted sql
--changeset boryst:000084_add_environment_to_traces_spans_threads
--comment: Add environment column to traces, spans, and trace_threads tables for environment-based filtering and sorting (OPIK-6265)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS environment LowCardinality(String) DEFAULT '';

-- set(0) is used instead of bloom_filter: environment has low cardinality (typically a handful of values
-- per workspace, e.g. development/staging/production), so bloom_filter would say "maybe" for almost every
-- granule and skip nothing. set(0) stores the exact set of values per granule, enabling precise skipping
-- on equality filters.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_traces_environment environment TYPE set(0) GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS environment LowCardinality(String) DEFAULT '';

-- set(0) for the same low-cardinality rationale as idx_traces_environment above.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_spans_environment environment TYPE set(0) GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS environment LowCardinality(String) DEFAULT '';

-- set(0) for the same low-cardinality rationale as idx_traces_environment above.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_trace_threads_environment environment TYPE set(0) GRANULARITY 1;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_traces_environment;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_spans_environment;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_trace_threads_environment;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS environment;
