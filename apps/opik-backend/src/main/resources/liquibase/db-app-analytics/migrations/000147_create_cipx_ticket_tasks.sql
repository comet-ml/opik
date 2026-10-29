--liquibase formatted sql
--changeset petrot:000147_create_cipx_ticket_tasks
--comment: Links between tickets and session tasks, written by the two-lane session analysis

-- Lane 2 writes the links (lane 1 writes no columns here); a link the analysis drops is marked is_deleted.
-- Ordered by ticket_key for ticket reads; the session_id index serves the writer's per-session lookup.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_ticket_tasks ON CLUSTER '{cluster}'
(
    workspace_id String,
    project_id   FixedString(36),
    ticket_key   String,
    session_id   String,
    task_id      FixedString(36),
    analyzed_at  DateTime64(6, 'UTC') DEFAULT now64(6),
    is_deleted   UInt8 DEFAULT 0,
    INDEX idx_session_id session_id TYPE bloom_filter GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_ticket_tasks',
    '{replica}',
    analyzed_at,
    is_deleted
)
ORDER BY (workspace_id, project_id, ticket_key, session_id, task_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_ticket_tasks ON CLUSTER '{cluster}';
