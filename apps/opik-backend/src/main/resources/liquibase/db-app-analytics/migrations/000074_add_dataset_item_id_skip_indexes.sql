--liquibase formatted sql
--changeset andrescrz:000074_add_dataset_item_id_skip_indexes
--comment: Add skip indexes on dataset_item_id for dataset_item_versions to speed up lookups, cursor pagination, and deduplication (OPIK-4518)

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_dataset_item_versions_dataset_item_id_bf dataset_item_id TYPE bloom_filter(0.01) GRANULARITY 1;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    ADD INDEX IF NOT EXISTS idx_dataset_item_versions_dataset_item_id_minmax dataset_item_id TYPE minmax GRANULARITY 1;

-- Materialize indexes on existing data parts (runs as an async mutation in ClickHouse)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_dataset_item_versions_dataset_item_id_bf;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_dataset_item_versions_dataset_item_id_minmax;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_dataset_item_versions_dataset_item_id_minmax;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_dataset_item_versions_dataset_item_id_bf;
