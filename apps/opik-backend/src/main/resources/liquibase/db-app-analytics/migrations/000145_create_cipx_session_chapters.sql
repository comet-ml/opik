--liquibase formatted sql
--changeset petrot:000145_create_cipx_session_chapters
--comment: One row per chapter of a task in an analysed session, written by the two-lane session analysis

-- Lane 1 writes labels and bounds (task_id, turns, trace ids, phase, is_deleted); lane 2 writes title and summary.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_chapters ON CLUSTER '{cluster}'
(
    workspace_id   String,
    project_id     FixedString(36),
    session_id     String,
    chapter_id     FixedString(36),
    task_id        FixedString(36),
    first_trace_id FixedString(36),
    last_trace_id  FixedString(36),
    start_turn     UInt32,
    end_turn       UInt32,
    phase          LowCardinality(String),
    title          String,
    summary        String,
    analyzed_at    DateTime64(6, 'UTC') DEFAULT now64(6),
    is_deleted     UInt8 DEFAULT 0
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_session_chapters',
    '{replica}',
    analyzed_at,
    is_deleted
)
ORDER BY (workspace_id, project_id, session_id, chapter_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_chapters ON CLUSTER '{cluster}';
