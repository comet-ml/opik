--liquibase formatted sql

--changeset liyaka:change-tables-to-replicated-01 id:create-automation-rule-evaluator-logs
CREATE TABLE automation_rule_evaluator_logs1
(
    `timestamp` DateTime64(9, 'UTC') DEFAULT now64(9),
    `workspace_id` String,
    `rule_id` FixedString(36),
    `level` Enum8('TRACE' = 0, 'DEBUG' = 1, 'INFO' = 2, 'WARM' = 3, 'ERROR' = 4),
    `message` String,
    `markers` Map(String, String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/opik_dev/automation_rule_evaluator_logs', '{replica}')
ORDER BY (workspace_id, rule_id, timestamp)
TTL toDateTime(timestamp + toIntervalMonth(6))
SETTINGS index_granularity = 8192;
--rollback DROP TABLE automation_rule_evaluator_logs1;

--changeset liyaka:change-tables-to-replicated-02 id:migrate-automation-rule-evaluator-logs
ALTER TABLE automation_rule_evaluator_logs1 ATTACH PARTITION tuple() FROM automation_rule_evaluator_logs;
DROP TABLE automation_rule_evaluator_logs SYNC max_table_size_to_drop = 0;
RENAME TABLE automation_rule_evaluator_logs1 TO automation_rule_evaluator_logs;
--rollback RENAME TABLE automation_rule_evaluator_logs TO automation_rule_evaluator_logs1;

--changeset liyaka:change-tables-to-replicated-03 id:create-comments
CREATE TABLE comments1
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/comments','{replica}',last_updated_at)
ORDER BY (workspace_id, project_id, entity_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE comments1;

--changeset liyaka:change-tables-to-replicated-04 id:migrate-comments
ALTER TABLE comments1 ATTACH PARTITION tuple() FROM comments;
DROP TABLE comments SYNC max_table_size_to_drop = 0;
RENAME TABLE comments1 TO comments;
--rollback RENAME TABLE comments TO comments1;

--changeset liyaka:change-tables-to-replicated-05 id:create-dataset-items
CREATE TABLE IF NOT EXISTS dataset_items1
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/dataset_items', '{replica}',last_updated_at)
ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE dataset_items1;

--changeset liyaka:change-tables-to-replicated-06 id:migrate-dataset-items
ALTER TABLE dataset_items1 ATTACH PARTITION tuple() FROM dataset_items;
DROP TABLE dataset_items SYNC max_table_size_to_drop = 0;
RENAME TABLE dataset_items1 TO dataset_items;
--rollback RENAME TABLE dataset_items TO dataset_items1;

--changeset liyaka:change-tables-to-replicated-07 id:create-experiment-items
CREATE TABLE IF NOT EXISTS experiment_items1
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/experiment_items', '{replica}', last_updated_at)
ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE experiment_items1;

--changeset liyaka:change-tables-to-replicated-08 id:migrate-experiment-items
ALTER TABLE experiment_items1 ATTACH PARTITION tuple() FROM experiment_items;
DROP TABLE experiment_items SYNC max_table_size_to_drop = 0;
RENAME TABLE experiment_items1 TO experiment_items;
--rollback RENAME TABLE experiment_items TO experiment_items1;

--changeset liyaka:change-tables-to-replicated-09 id:create-experiments
CREATE TABLE IF NOT EXISTS experiments1
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/experiments', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE experiments1;

--changeset liyaka:change-tables-to-replicated-10 id:migrate-experiments
ALTER TABLE experiments1 ATTACH PARTITION tuple() FROM experiments;
DROP TABLE experiments SYNC max_table_size_to_drop = 0;
RENAME TABLE experiments1 TO experiments;
--rollback RENAME TABLE experiments TO experiments1;

--changeset liyaka:change-tables-to-replicated-11 id:create-feedback-scores
CREATE TABLE IF NOT EXISTS feedback_scores1
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/feedback_scores', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, entity_type, entity_id, name)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE feedback_scores1;

--changeset liyaka:change-tables-to-replicated-12 id:migrate-feedback-scores
ALTER TABLE feedback_scores1 ATTACH PARTITION tuple() FROM feedback_scores;
DROP TABLE feedback_scores SYNC max_table_size_to_drop = 0;
RENAME TABLE feedback_scores1 TO feedback_scores;
--rollback RENAME TABLE feedback_scores TO feedback_scores1;

--changeset liyaka:change-tables-to-replicated-13 id:create-spans
CREATE TABLE IF NOT EXISTS spans1
(
    `id` FixedString(36),
    `workspace_id` String,
    `project_id` FixedString(36),
    `trace_id` FixedString(36),
    `parent_span_id` String DEFAULT '',
    `name` String,
    `type` Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3),
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
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/spans', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE spans1;

--changeset liyaka:change-tables-to-replicated-14 id:migrate-spans
ALTER TABLE spans1 ATTACH PARTITION tuple() FROM spans;
DROP TABLE spans SYNC SETTINGS max_table_size_to_drop = 0;
RENAME TABLE spans1 TO spans;
--rollback RENAME TABLE spans TO spans1;

--changeset liyaka:change-tables-to-replicated-15 id:create-traces
CREATE TABLE IF NOT EXISTS traces1
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
    `error_info` String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/opik_dev/traces', '{replica}',last_updated_at)
ORDER BY (workspace_id, project_id, id)
SETTINGS index_granularity = 8192;
--rollback DROP TABLE traces1;

--changeset liyaka:change-tables-to-replicated-16 id:migrate-traces
ALTER TABLE traces1 ATTACH PARTITION tuple() FROM traces;
DROP TABLE traces SYNC max_table_size_to_drop = 0;
RENAME TABLE traces1 TO traces;
--rollback RENAME TABLE traces TO traces1;