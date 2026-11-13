--liquibase formatted sql
--changeset aswynz:000148_add_automation_target_history_table
--comment: Create automation_target_history, every item an automation ever wrote to a test suite or dataset, so it never writes the same one twice

-- The counterpart of annotation_queue_item_history for the other write targets. A person deleting a case
-- from a suite must not have automation put it straight back, and dataset items carry no trace id a
-- deletion would preserve, so existence is recorded here rather than inferred from the target. Keyed by
-- target rather than by automation: two automations writing to one suite share the guarantee.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_target_history ON CLUSTER '{cluster}'
(
    workspace_id     String,
    project_id       FixedString(36),
    target_type      Enum8('test_suite' = 1, 'dataset' = 2),
    target_id        FixedString(36),
    item_id          FixedString(36),
    automation_id    FixedString(36),
    created_at       DateTime64(9, 'UTC') DEFAULT now64(9),
    created_by       String DEFAULT '',
    last_updated_at  DateTime64(6, 'UTC') DEFAULT now64(6),
    last_updated_by  String DEFAULT ''
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/automation_target_history', '{replica}', last_updated_at)
ORDER BY (workspace_id, project_id, target_type, target_id, item_id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.automation_target_history ON CLUSTER '{cluster}';
