--liquibase formatted sql
--changeset idoberko2:000047_add_dataset_item_versions_table
--comment: Create dataset_item_versions table for immutable dataset version snapshots

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
(
    id                      FixedString(36),
    dataset_item_id         FixedString(36),
    dataset_id              FixedString(36),
    dataset_version_id      FixedString(36),
    data                    Map(String, String) DEFAULT map(),
    metadata                String DEFAULT '',
    source                  Enum8('unknown' = 0, 'sdk' = 1, 'manual' = 2, 'span' = 3, 'trace' = 4),
    trace_id                String DEFAULT '',
    span_id                 String DEFAULT '',
    tags                    Array(String) DEFAULT [],
    item_created_at         DateTime64(9, 'UTC'),
    item_last_updated_at    DateTime64(9, 'UTC'),
    item_created_by         String DEFAULT '',
    item_last_updated_by    String DEFAULT '',
    created_at              DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at         DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by              String DEFAULT '',
    last_updated_by         String DEFAULT '',
    workspace_id            String,
    data_hash               UInt64 MATERIALIZED xxHash64(toString(data))
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/dataset_item_versions', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, dataset_version_id, id)
SETTINGS index_granularity = 8192;

-- Add data_hash to dataset_items (draft items) for version comparison
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS data_hash UInt64 MATERIALIZED xxHash64(toString(data));

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}';
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS data_hash;
