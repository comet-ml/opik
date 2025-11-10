--liquibase formatted sql
--changeset idoberko2:add_dataset_item_versions_table
--comment: Create dataset_item_versions table for immutable dataset version snapshots

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions
(
    id                  FixedString(36),
    dataset_id          FixedString(36),
    version_id          FixedString(36),
    data                Map(String, String) DEFAULT map(),
    source              Enum8('unknown' = 0, 'sdk' = 1, 'manual' = 2, 'span' = 3, 'trace' = 4),
    trace_id            String DEFAULT '',
    span_id             String DEFAULT '',
    created_at          DateTime64(9, 'UTC') DEFAULT now64(9),
    version_created_at  DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at     DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by          String DEFAULT '',
    last_updated_by     String DEFAULT '',
    workspace_id        String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/dataset_item_versions', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, version_id, id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions;

