--liquibase formatted sql
--changeset guys:000125_add_minmax_index_trace_threads_created_at
--comment: Add minmax skip index on trace_threads.created_at. The table is sorted by (workspace_id, project_id, thread_id, id), so a created_at filter scans the whole matched workspace range instead of pruning to the window. Apply after prior mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'trace_threads'.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_trace_threads_created_at created_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_trace_threads_created_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_trace_threads_created_at;
