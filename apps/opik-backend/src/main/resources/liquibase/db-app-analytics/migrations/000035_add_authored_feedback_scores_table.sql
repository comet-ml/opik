--liquibase formatted sql
--changeset ido:000035_add_authored_feedback_scores

CREATE TABLE ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}'
(
    entity_id       FixedString(36),
    entity_type     Enum8('unknown' = 0, 'span' = 1, 'trace' = 2, 'thread' = 3),
    project_id      FixedString(36),
    workspace_id    String,
    author          String,
    name            String,
    category_name   String               default '',
    value           Decimal(18, 9),
    reason          String               default '',
    source          Enum8('sdk' = 1, 'ui' = 2, 'online_scoring' = 3),
    created_at      DateTime64(9, 'UTC') default now64(9),
    last_updated_at DateTime64(9, 'UTC') default now64(9),
    created_by      String               default '',
    last_updated_by String               default ''
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/authored_feedback_scores', '{replica}', last_updated_at)
        ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name)
        SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.authored_feedback_scores ON CLUSTER '{cluster}';
