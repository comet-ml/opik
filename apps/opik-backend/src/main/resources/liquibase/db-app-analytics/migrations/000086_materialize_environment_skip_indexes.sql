--liquibase formatted sql
--changeset boryst:000086_materialize_environment_skip_indexes
--comment: Materialize environment skip indexes added unmaterialized in 000084 (OPIK-6780). Apply after prior mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table IN ('traces','spans','trace_threads').

-- Materialize index from 000084 (idx_traces_environment was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_traces_environment;

-- Materialize index from 000084 (idx_spans_environment was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_spans_environment;

-- Materialize index from 000084 (idx_trace_threads_environment was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_trace_threads_environment;

--rollback empty
