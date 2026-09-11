--liquibase formatted sql
--changeset andrescrz:000113_add_id_bloom_filter_index_to_traces
--comment: OPIK-7483 - Add a bloom_filter skip index on traces.id for exact-match / IN lookups (delete-by-id project resolution, getPartialById, findByIds). The (workspace_id, project_id, id) sort key can't prune by id alone because id is not a usable prefix, so these lookups otherwise scan the whole workspace. Mirrors traces_local_v2 (000101). Added and materialized so existing parts are covered too (MATERIALIZE INDEX submits an async background mutation - monitor via SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'traces').

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_traces_id_bf id TYPE bloom_filter(0.01) GRANULARITY 1,
    MATERIALIZE INDEX idx_traces_id_bf;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_traces_id_bf;
