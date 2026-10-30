--liquibase formatted sql
--changeset petrot:000144_create_cipx_session_tasks
--comment: One row per task of an analysed session, written by the two-lane session analysis

-- Lane 1 writes labels and bounds (turns, trace ids, kind, outcome, is_deleted); lane 2 writes name and summary.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_tasks ON CLUSTER '{cluster}'
(
    workspace_id   String,
    project_id     FixedString(36),
    session_id     String,
    task_id        FixedString(36),
    first_trace_id FixedString(36),
    last_trace_id  FixedString(36),
    start_turn     UInt32,
    end_turn       UInt32,
    kind           LowCardinality(String),
    outcome        LowCardinality(String),
    name           String,
    summary        String,
    analyzed_at    DateTime64(6, 'UTC') DEFAULT now64(6),
    is_deleted     UInt8 DEFAULT 0
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_session_tasks',
    '{replica}',
    analyzed_at,
    is_deleted
)
ORDER BY (workspace_id, project_id, session_id, task_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_tasks ON CLUSTER '{cluster}';
