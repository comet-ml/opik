--liquibase formatted sql
--changeset andriid:000119_create_cipx_session_analysis
--comment: LLM session analysis results (phase segmentation), written by the cost API analysis runner

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
(
    workspace_id  String,
    project_id    FixedString(36),
    user_uuid     String,
    session_id    String,

    task_version  UInt16,
    analyzed_at   DateTime64(6, 'UTC') DEFAULT now64(6),
    source_turns  UInt32,

    `segments.start_turn`     Array(UInt32),
    `segments.end_turn`       Array(UInt32),
    `segments.first_trace_id` Array(FixedString(36)),
    `segments.last_trace_id`  Array(FixedString(36)),
    `segments.phase`          Array(LowCardinality(String)),
    `segments.title`          Array(String)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_session_analysis',
    '{replica}',
    analyzed_at
)
ORDER BY (workspace_id, project_id, session_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}';
