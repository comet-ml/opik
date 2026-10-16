--liquibase formatted sql
--changeset boryst:000137_create_cipx_tickets
--comment: Tickets that group session analysis tasks by their ticket key, written by the cost API analysis runner (OPIK-8834)

-- A ticket is identified by its external key (e.g. OPIK-1234, stored upper-case), so rows for
-- one key merge however many analyses write it. Its tasks are the cipx_session_analysis tasks
-- whose `tasks.ticket_key` is this key; spend, sessions and engineers are derived from them.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_tickets ON CLUSTER '{cluster}'
(
    workspace_id    String,
    project_id      FixedString(36),
    key             String,

    title           String DEFAULT '',
    description     String DEFAULT '',
    kind            LowCardinality(String) DEFAULT '',

    created_at      DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_tickets',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, key);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_tickets ON CLUSTER '{cluster}';

