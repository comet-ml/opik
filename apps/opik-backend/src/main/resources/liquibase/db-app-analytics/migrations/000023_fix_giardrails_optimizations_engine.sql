--liquibase formatted sql

--changeset liyaka:fix-guardrails-engine-01-cluster id:drop-guardrails-table-cluster context:cluster
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0;
--rollback -- Cannot rollback drop table operation

--changeset liyaka:fix-guardrails-engine-01-standalone id:drop-guardrails-table-standalone context:standalone
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails SYNC SETTINGS max_table_size_to_drop = 0;
--rollback -- Cannot rollback drop table operation

--changeset liyaka:fix-guardrails-engine-02-cluster id:create-guardrails-table-cluster context:cluster
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails ON CLUSTER '{cluster}'
(
   `id` FixedString(36),
    `entity_id` FixedString(36),
    `entity_type` Enum8('unknown' = 0, 'span' = 1, 'trace' = 2),
    `secondary_entity_id` FixedString(36),
    `project_id` FixedString(36),
    `workspace_id` String,
    `name` String,
    `result` Enum8('passed' = 0, 'failed' = 1),
    `config` String,
    `details` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String,
    `last_updated_by` String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/guardrails', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, entity_type, entity_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails ON CLUSTER '{cluster}';

--changeset liyaka:fix-guardrails-engine-02-standalone id:create-guardrails-table-standalone context:standalone
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails
(
   `id` FixedString(36),
    `entity_id` FixedString(36),
    `entity_type` Enum8('unknown' = 0, 'span' = 1, 'trace' = 2),
    `secondary_entity_id` FixedString(36),
    `project_id` FixedString(36),
    `workspace_id` String,
    `name` String,
    `result` Enum8('passed' = 0, 'failed' = 1),
    `config` String,
    `details` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String,
    `last_updated_by` String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/guardrails', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, entity_type, entity_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.guardrails;

--changeset liyaka:fix-optimizations-engine-01-cluster id:drop-optimizations-table-cluster context:cluster
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0;
--rollback -- Cannot rollback drop table operation

--changeset liyaka:fix-optimizations-engine-01-standalone id:drop-optimizations-table-standalone context:standalone
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations SYNC SETTINGS max_table_size_to_drop = 0;
--rollback -- Cannot rollback drop table operation

--changeset liyaka:fix-optimizations-engine-02-cluster id:create-optimizations-table-cluster context:cluster
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
(
    `workspace_id` String,
    `dataset_id` FixedString(36),
    `id` FixedString(36),
    `name` String,
    `objective_name` String,
    `status` Enum8('running' = 0, 'completed' = 1, 'cancelled' = 2),
    `metadata` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String,
    `last_updated_by` String,
    `dataset_deleted` Bool DEFAULT false
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimizations', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}';

--changeset liyaka:fix-optimizations-engine-02-standalone id:create-optimizations-table-standalone context:standalone
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations
(
    `workspace_id` String,
    `dataset_id` FixedString(36),
    `id` FixedString(36),
    `name` String,
    `objective_name` String,
    `status` Enum8('running' = 0, 'completed' = 1, 'cancelled' = 2),
    `metadata` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String,
    `last_updated_by` String,
    `dataset_deleted` Bool DEFAULT false
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimizations', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations;