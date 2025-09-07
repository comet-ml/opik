--liquibase formatted sql
--changeset yariv:add_annotation_queues_tables
--comment: Create annotation queues tables for SME review workflows

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues
(
    workspace_id        String,
    project_id          FixedString(36),
    id                  FixedString(36),
    name                String,
    description         String,
    instructions        String,
    comments_enabled    UInt8 DEFAULT 1,
    feedback_definitions Array(FixedString(36)),
    scope               Enum8('trace' = 0, 'thread' = 1),
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String DEFAULT 'admin',
    last_updated_at     DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by     String DEFAULT 'admin'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/annotation_queues', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queues;

--changeset yariv:add_annotation_queue_items_table
--comment: Create annotation queue items junction table

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items
(
    workspace_id        String,
    queue_id            FixedString(36),
    item_id             FixedString(36),
    item_type           Enum8('trace' = 0, 'thread' = 1),
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String DEFAULT 'admin',
    last_updated_at     DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by     String DEFAULT 'admin'
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/annotation_queue_items', '{replica}')
ORDER BY (workspace_id, queue_id, item_id)
SETTINGS index_granularity = 8192;

-- Index for item-based queries (finding which queues an item belongs to)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items 
ADD INDEX idx_workspace_item_type (workspace_id, item_id, item_type) TYPE bloom_filter GRANULARITY 4;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_items;
