--liquibase formatted sql
--changeset borystkachenko:000037_add_annotation_queues_table
--comment: Create annotation-queues table for human annotation workflows

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}'
(
    id              FixedString(36),
    workspace_id    String,
    project_id      FixedString(36),
    name            String,
    description     String               DEFAULT '',
    instructions    String               DEFAULT '',
    scope           Enum8('trace' = 1, 'thread' = 2),
    comments_enabled Boolean             DEFAULT false,
    feedback_definitions Array(FixedString(36)),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by      String               DEFAULT '',
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_by String               DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/annotation_queues', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues ON CLUSTER '{cluster}';

