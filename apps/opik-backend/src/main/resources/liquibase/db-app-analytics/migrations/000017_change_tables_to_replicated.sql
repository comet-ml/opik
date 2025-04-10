--liquibase formatted sql

--changeset liyaka:change-tables-to-replicated-01 id:create-automation-rule-evaluator-logs
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs1
(
    `timestamp` DateTime64(9, 'UTC') DEFAULT now64(9),
    `workspace_id` String,
    `rule_id` FixedString(36),
    `level` Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARM' = 3, 'ERROR' = 4),
    `message` String,
    `markers` Map(String, String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/automation_rule_evaluator_logs', '{replica}')
ORDER BY (workspace_id, rule_id, timestamp)
TTL toDateTime(timestamp + toIntervalMonth(6))
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs1;

--changeset liyaka:change-tables-to-replicated-02 id:migrate-automation-rule-evaluator-logs
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs1 TO ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs TO ${ANALYTICS_DB_DATABASE_NAME}.automation_rule_evaluator_logs1;

--changeset liyaka:change-tables-to-replicated-03 id:create-comments
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.comments1
(
    `id` FixedString(36),
    `entity_id` FixedString(36),
    `entity_type` Enum8('trace' = 1, 'span' = 2),
    `project_id` FixedString(36),
    `workspace_id` String,
    `text` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/comments','{replica}',last_updated_at)
ORDER BY (workspace_id, project_id, entity_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments1;

--changeset liyaka:change-tables-to-replicated-04 id:migrate-comments
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.comments;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments1 TO ${ANALYTICS_DB_DATABASE_NAME}.comments;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments TO ${ANALYTICS_DB_DATABASE_NAME}.comments1;

--changeset liyaka:change-tables-to-replicated-05 id:create-dataset-items
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_items1
(
    `workspace_id` String,
    `dataset_id` FixedString(36),
    `source` Enum8('unknown' = 0, 'sdk' = 1, 'manual' = 2, 'span' = 3, 'trace' = 4),
    `trace_id` String DEFAULT '',
    `span_id` String DEFAULT '',
    `id` FixedString(36),
    `input` String DEFAULT '',
    `expected_output` String DEFAULT '',
    `metadata` String DEFAULT '',
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT '',
    `data` Map(String, String) DEFAULT map()
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/dataset_items', '{replica}',last_updated_at)
ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items1;

--changeset liyaka:change-tables-to-replicated-06 id:migrate-dataset-items
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.dataset_items;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items1 TO ${ANALYTICS_DB_DATABASE_NAME}.dataset_items;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items TO ${ANALYTICS_DB_DATABASE_NAME}.dataset_items1;

--changeset liyaka:change-tables-to-replicated-07 id:create-experiment-items
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_items1
(
    `id` FixedString(36),
    `experiment_id` FixedString(36),
    `dataset_item_id` FixedString(36),
    `trace_id` FixedString(36),
    `workspace_id` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/experiment_items', '{replica}', last_updated_at)
ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items1;

--changeset liyaka:change-tables-to-replicated-08 id:migrate-experiment-items
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_items;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items1 TO ${ANALYTICS_DB_DATABASE_NAME}.experiment_items;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items TO ${ANALYTICS_DB_DATABASE_NAME}.experiment_items1;

--changeset liyaka:change-tables-to-replicated-09 id:create-experiments
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiments1
(
    `workspace_id` String,
    `dataset_id` FixedString(36),
    `id` FixedString(36),
    `name` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT '',
    `metadata` String DEFAULT '',
    `prompt_version_id` Nullable(FixedString(36)) DEFAULT NULL,
    `prompt_id` Nullable(FixedString(36)) DEFAULT NULL,
    `prompt_versions` Map(FixedString(36), Array(FixedString(36)))
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/experiments', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments1;

--changeset liyaka:change-tables-to-replicated-10 id:migrate-experiments
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.experiments;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments1 TO ${ANALYTICS_DB_DATABASE_NAME}.experiments;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments TO ${ANALYTICS_DB_DATABASE_NAME}.experiments1;

--changeset liyaka:change-tables-to-replicated-11 id:create-feedback-scores
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores1
(
    `entity_id` FixedString(36),
    `entity_type` Enum8('unknown' = 0, 'span' = 1, 'trace' = 2),
    `project_id` FixedString(36),
    `workspace_id` String,
    `name` String,
    `category_name` String DEFAULT '',
    `value` Decimal(18, 9),
    `reason` String DEFAULT '',
    `source` Enum8('sdk' = 1, 'ui' = 2, 'online_scoring' = 3),
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/feedback_scores', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, entity_type, entity_id, name)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores1;

--changeset liyaka:change-tables-to-replicated-12 id:migrate-feedback-scores
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores1 TO ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores TO ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores1;

--changeset liyaka:change-tables-to-replicated-13 id:create-spans
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans1
(
    `id` FixedString(36),
    `workspace_id` String,
    `project_id` FixedString(36),
    `trace_id` FixedString(36),
    `parent_span_id` String DEFAULT '',
    `name` String,
    `type` Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4),
    `start_time` DateTime64(9, 'UTC') DEFAULT now64(9),
    `end_time` Nullable(DateTime64(9, 'UTC')),
    `input` String DEFAULT '',
    `output` String DEFAULT '',
    `metadata` String DEFAULT '',
    `tags` Array(String),
    `usage` Map(String, Int32),
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT '',
    `model` String DEFAULT '',
    `provider` String DEFAULT '',
    `total_estimated_cost` Decimal(38, 12),
    `total_estimated_cost_version` String DEFAULT '',
    `error_info` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/spans', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans1;

--changeset liyaka:change-tables-to-replicated-14 id:migrate-spans
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.spans;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans1 TO ${ANALYTICS_DB_DATABASE_NAME}.spans;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans TO ${ANALYTICS_DB_DATABASE_NAME}.spans1;

--changeset liyaka:change-tables-to-replicated-15 id:create-traces
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces1
(
    `id` FixedString(36),
    `workspace_id` String,
    `project_id` FixedString(36),
    `name` String,
    `start_time` DateTime64(9, 'UTC') DEFAULT now64(9),
    `end_time` Nullable(DateTime64(9, 'UTC')),
    `input` String DEFAULT '',
    `output` String DEFAULT '',
    `metadata` String,
    `tags` Array(String),
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT '',
    `error_info` String DEFAULT '',
    `thread_id` String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/traces', '{replica}',last_updated_at)
ORDER BY (workspace_id, project_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces1;

--changeset liyaka:change-tables-to-replicated-16 id:migrate-traces
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.traces;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces1 TO ${ANALYTICS_DB_DATABASE_NAME}.traces;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces1;

--changeset liyaka:change-tables-to-replicated-17 id:create-attachments
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.attachments1
(
    `workspace_id` String,
    `container_id` FixedString(36),
    `entity_id` FixedString(36),
    `entity_type` Enum8('trace' = 1, 'span' = 2),
    `file_name` String,
    `mime_type` String,
    `file_size` Int64,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `deleted_at` DateTime64(9, 'UTC') DEFAULT toDateTime64(0, 9),
    `created_by` String DEFAULT '',
    `last_updated_by` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/attachments', '{replica}',last_updated_at)
ORDER BY (workspace_id, container_id, entity_type, entity_id, file_name)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments1;

--changeset liyaka:change-tables-to-replicated-18 id:migrate-attachments
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments1 ATTACH PARTITION tuple() FROM ${ANALYTICS_DB_DATABASE_NAME}.attachments;
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments1 TO ${ANALYTICS_DB_DATABASE_NAME}.attachments;
--rollback RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.attachments TO ${ANALYTICS_DB_DATABASE_NAME}.attachments1;

--changeset liyaka:change-tables-to-replicated-19 id:migrate-databasechangelog
CREATE TABLE IF NOT EXISTS default.DATABASECHANGELOG1
(
    `ID` String,
    `AUTHOR` String,
    `FILENAME` String,
    `DATEEXECUTED` DateTime64(3),
    `ORDEREXECUTED` UInt64,
    `EXECTYPE` String,
    `MD5SUM` Nullable(String),
    `DESCRIPTION` Nullable(String),
    `COMMENTS` Nullable(String),
    `TAG` Nullable(String),
    `LIQUIBASE` Nullable(String),
    `CONTEXTS` Nullable(String),
    `LABELS` Nullable(String),
    `DEPLOYMENT_ID` Nullable(String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/default/DATABASECHANGELOG', '{replica}')
ORDER BY ID
SETTINGS index_granularity = 8192
--rollback DROP TABLE default.DATABASECHANGELOG1;

--changeset liyaka:change-tables-to-replicated-20 id:migrate-databasechangelog
ALTER TABLE default.DATABASECHANGELOG1 ATTACH PARTITION tuple() FROM default.DATABASECHANGELOG;
ALTER TABLE default.DATABASECHANGELOG DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE default.DATABASECHANGELOG SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE default.DATABASECHANGELOG1 TO default.DATABASECHANGELOG;
--rollback RENAME TABLE default.DATABASECHANGELOG TO default.DATABASECHANGELOG1;

--changeset liyaka:change-tables-to-replicated-21 id:migrate-databasechangeloglock
CREATE TABLE IF NOT EXISTS default.DATABASECHANGELOGLOCK1
(
    `ID` Int64,
    `LOCKED` UInt8,
    `LOCKGRANTED` Nullable(DateTime64(3)),
    `LOCKEDBY` Nullable(String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/default/DATABASECHANGELOGLOCK', '{replica}')
ORDER BY ID
SETTINGS index_granularity = 8192
--rollback DROP TABLE default.DATABASECHANGELOGLOCK1;

--changeset liyaka:change-tables-to-replicated-22 id:migrate-databasechangeloglock
ALTER TABLE default.DATABASECHANGELOGLOCK1 ATTACH PARTITION tuple() FROM default.DATABASECHANGELOGLOCK;
ALTER TABLE default.DATABASECHANGELOGLOCK DETACH PARTITION tuple() SETTINGS max_partition_size_to_drop = 0;
DROP TABLE default.DATABASECHANGELOGLOCK SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE default.DATABASECHANGELOGLOCK1 TO default.DATABASECHANGELOGLOCK;
--rollback RENAME TABLE default.DATABASECHANGELOGLOCK TO default.DATABASECHANGELOGLOCK1;