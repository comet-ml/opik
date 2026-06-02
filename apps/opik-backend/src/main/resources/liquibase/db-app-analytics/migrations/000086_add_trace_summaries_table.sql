--liquibase formatted sql
--changeset petrot:000086_add_trace_summaries_table
--comment: Stores LLM-generated plain-text summaries for traces, keyed by trace id

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_summaries ON CLUSTER '{cluster}'
(
    id              FixedString(36),
    workspace_id    String,
    project_id      FixedString(36),
    summary         String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/trace_summaries',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_summaries ON CLUSTER '{cluster}';
