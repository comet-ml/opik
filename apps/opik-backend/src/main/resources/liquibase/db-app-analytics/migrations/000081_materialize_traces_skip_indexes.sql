--liquibase formatted sql
--changeset thiagohora:000081_materialize_traces_skip_indexes
--comment: Materialize skip indexes on traces (~5.77TB/288M rows). Apply after 000080 mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'traces'.

-- Materialize index from 000075 (idx_traces_source was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_traces_source;

-- Materialize index from 000077 (idx_traces_thread_id_bf was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_traces_thread_id_bf;

--rollback empty
