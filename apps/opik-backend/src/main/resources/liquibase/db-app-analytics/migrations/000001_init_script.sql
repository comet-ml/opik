--liquibase formatted sql
--changeset andrescrz:init_script

CREATE DATABASE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME};

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans
(
    id              FixedString(36),
    workspace_id    String,
    project_id      FixedString(36),
    trace_id        FixedString(36),
    parent_span_id  String               DEFAULT '',
    name            String,
    type            Enum8('unknown' = 0 , 'general' = 1, 'tool' = 2, 'llm' = 3),
    start_time      DateTime64(9, 'UTC') DEFAULT now64(9),
    end_time        Nullable(DateTime64(9, 'UTC')),
    input           String               DEFAULT '',
    output          String               DEFAULT '',
    metadata        String               DEFAULT '',
    tags            Array(String),
    usage           Map(String, Int32),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
      ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id);

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces
(
    id              FixedString(36),
    workspace_id    String,
    project_id      FixedString(36),
    name            String,
    start_time      DateTime64(9, 'UTC') DEFAULT now64(9),
    end_time        Nullable(DateTime64(9, 'UTC')),
    input           String DEFAULT '',
    output          String DEFAULT '',
    metadata        String,
    tags            Array(String),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, project_id, id);

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores
(
    entity_id       FixedString(36),
    entity_type     ENUM('unknown' = 0 , 'span' = 1, 'trace' = 2),
    project_id      FixedString(36),
    workspace_id    String,
    name            String,
    category_name   String               DEFAULT '',
    value           Decimal32(4),
    reason          String               DEFAULT '',
    source          Enum8('sdk', 'ui'),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, project_id, entity_type, entity_id, name);

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
(
    workspace_id    String,
    dataset_id      FixedString(36),
    source          ENUM('unknown' = 0 , 'sdk' = 1, 'manual' = 2, 'span' = 3, 'trace' = 4),
    trace_id        String               DEFAULT '',
    span_id         String               DEFAULT '',
    id              FixedString(36),
    input           String               DEFAULT '',
    expected_output String               DEFAULT '',
    metadata        String               DEFAULT '',
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id);

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiments
(
    workspace_id    String,
    dataset_id      FixedString(36),
    id              FixedString(36),
    name            String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, dataset_id, id);


CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiment_items
(
    id              FixedString(36),
    experiment_id   FixedString(36),
    dataset_item_id FixedString(36),
    trace_id        FixedString(36),
    workspace_id    String,
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(9, 'UTC') DEFAULT now64(9)
) ENGINE = ReplacingMergeTree(last_updated_at)
    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id);

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN created_by String DEFAULT '',
    ADD COLUMN last_updated_by String DEFAULT '';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_items DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.dataset_items DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores DROP COLUMN created_by, DROP COLUMN last_updated_by;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.experiments;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.dataset_items;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces;
--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans;
--rollback DROP DATABASE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME};