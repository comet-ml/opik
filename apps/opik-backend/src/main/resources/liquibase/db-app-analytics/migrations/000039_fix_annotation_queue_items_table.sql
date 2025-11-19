--liquibase formatted sql
--changeset borystkachenko:000039_fix_annotation_queue_items_table

DROP TABLE IF EXISTS IF EMPTY ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0;
--rollback --rollback empty -- Cannot rollback drop table operation

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items  ON CLUSTER '{cluster}'
(
    workspace_id        String,
    project_id          FixedString(36),
    queue_id            FixedString(36),
    item_id             FixedString(36),
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String DEFAULT '',
    last_updated_at     DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_by     String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/annotation_queue_items', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, queue_id, item_id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items ON CLUSTER '{cluster}';
