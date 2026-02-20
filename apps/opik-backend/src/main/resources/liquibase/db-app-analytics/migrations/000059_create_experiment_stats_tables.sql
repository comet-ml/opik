--liquibase formatted sql
--changeset thiaghora:000059_create_experiment_aggregates_and_experiment_item_aggregates_tables
--comment: Create experiment_aggregates and experiment_item_aggregates tables for storing aggregated metrics at the experiment and experiment item level, respectively.

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
(
    `workspace_id` String,
    `id` FixedString(36),
    `dataset_id` FixedString(36),
    `project_id` FixedString(36),
    `name` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT '',
    `metadata` String DEFAULT '',
    `prompt_versions` Map(FixedString(36), Array(FixedString(36))) DEFAULT map(),
    `optimization_id` String DEFAULT '',
    `dataset_version_id` String DEFAULT '',
    `tags` Array(String) DEFAULT [],
    `type` Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2) DEFAULT 'regular',
    `status` Enum8('unknown' = 0, 'running' = 1, 'completed' = 2, 'cancelled' = 3) DEFAULT 'unknown',
    `experiment_scores` Map(String, Float64) DEFAULT map(),
    `trace_count` UInt64 DEFAULT 0,
    `experiment_items_count` UInt64 DEFAULT 0,
    `duration_percentiles` Map(String, Float64) DEFAULT map(),
    `feedback_scores_percentiles` Map(String, Map(String, Float64)) DEFAULT map(),
    `feedback_scores_avg` Map(String, Float64) DEFAULT map(),
    `total_estimated_cost_sum` Float64 DEFAULT 0,
    `total_estimated_cost_avg` Float64 DEFAULT 0,
    `total_estimated_cost_percentiles` Map(String, Float64) DEFAULT map(),
    `usage_avg` Map(String, Float64) DEFAULT map(),
    `usage_total_tokens_percentiles` Map(String, Float64) DEFAULT map()
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/experiment_aggregates', '{replica}', last_updated_at)
ORDER BY (workspace_id, id)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
(
    `workspace_id` String,
    `id` FixedString(36),
    `project_id` FixedString(36),
    `experiment_id` FixedString(36),
    `dataset_item_id` FixedString(36),
    `trace_id` FixedString(36),
    `duration` Decimal64(9),
    `total_estimated_cost` Decimal(38, 12),
    `usage` Map(String, Int64) DEFAULT map(),
    `feedback_scores` Map(String, Decimal64(9)) DEFAULT map(),
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/experiment_item_aggregates', '{replica}', last_updated_at)
ORDER BY (workspace_id, experiment_id, project_id, id)
SETTINGS index_granularity = 8192;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_aggregates_dataset_id dataset_id TYPE minmax GRANULARITY 4;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_aggregates_project_id project_id TYPE minmax GRANULARITY 4;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_aggregates_optimization_id optimization_id TYPE minmax GRANULARITY 4;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_aggregates_dataset_version_id dataset_version_id TYPE minmax GRANULARITY 4;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_item_aggregates_dataset_item_id dataset_item_id TYPE minmax GRANULARITY 4;

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}'
ADD INDEX idx_experiment_item_aggregates_trace_id trace_id TYPE minmax GRANULARITY 4;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_aggregates_dataset_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_aggregates_project_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_aggregates_optimization_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_aggregates_dataset_version_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_item_aggregates_dataset_item_id;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}' DROP INDEX IF EXISTS idx_experiment_item_aggregates_trace_id;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}';
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates ON CLUSTER '{cluster}';
