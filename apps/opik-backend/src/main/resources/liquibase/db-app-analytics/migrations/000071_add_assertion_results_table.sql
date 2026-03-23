--liquibase formatted sql
--changeset danield:000071_add_assertion_results_table
--comment: Create dedicated assertion_results table to separate assertion data from feedback_scores

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.assertion_results ON CLUSTER '{cluster}'
(
    entity_id       FixedString(36),
    entity_type     Enum8('unknown' = 0, 'span' = 1, 'trace' = 2, 'thread' = 3),
    project_id      FixedString(36),
    workspace_id    String,
    name            String,
    passed          Enum8('passed' = 0, 'failed' = 1),
    reason          String               DEFAULT '',
    source          Enum8('sdk' = 1, 'ui' = 2, 'online_scoring' = 3),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    author          String               DEFAULT '',
    created_by      String               DEFAULT '',
    last_updated_by String               DEFAULT ''
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/assertion_results', '{replica}', last_updated_at)
        ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name)
        SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.assertion_results ON CLUSTER '{cluster}';
