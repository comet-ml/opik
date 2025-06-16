--liquibase formatted sql
--changeset thiagohora:000028_add_trace_threads_table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}' (
    id                  FixedString(36),
    thread_id           String,
    project_id          FixedString(36),
    workspace_id        String,
    status              ENUM('unknown' = 0 , 'active' = 1, 'inactive' = 2),
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at     DateTime64(6, 'UTC') DEFAULT now64(6),
    created_by          String,
    last_updated_by     String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/trace_threads', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, thread_id, id)

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_threads ON CLUSTER '{cluster}';
