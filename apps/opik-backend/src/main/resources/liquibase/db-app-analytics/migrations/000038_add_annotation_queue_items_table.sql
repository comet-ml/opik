--liquibase formatted sql
--changeset borystkachenko:000038_add_annotation_queue_items_table
--comment: Create annotation_queue_items table for human annotation workflows

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items
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
