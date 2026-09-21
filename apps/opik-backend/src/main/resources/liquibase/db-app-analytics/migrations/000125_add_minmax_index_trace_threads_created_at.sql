--liquibase formatted sql
--changeset guys:000125_add_minmax_index_trace_threads_created_at
--comment: Add minmax skip index on trace_threads.created_at so day-bounded scans prune granules instead of reading every row a workspace owns. The table is sorted by (workspace_id, project_id, thread_id, id), so the nightly BI export's one-day window reads 22.1M rows to return 278K for the largest workspace, tripping the exporter's max_rows_to_read cap. Materialized here so existing parts benefit, as in 000073/000106 -- 000084 added indexes unmaterialized and needed 000086 to repair. Apply after prior mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'trace_threads'.

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_trace_threads_created_at created_at TYPE minmax GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_trace_threads_created_at;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_trace_threads_created_at;
