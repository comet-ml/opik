--liquibase formatted sql
--changeset andrescrz:000096_create_deletion_events_local
--comment: Append-only audit log of deletions, used to replay them across table migrations

-- A lightweight DELETE does not change a row's version column, so deletes issued while a table
-- is being copied into a new layout are invisible to the version-based delta step and would
-- reappear in the copy. This log records each delete so the copy step can replay the ids against
-- the destination. Append-only and read per-shard, hence MergeTree rather than Distributed. No
-- surrogate id: rows are keyed and replayed on (source_table, event_time, deleted_id).
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local ON CLUSTER '{cluster}'
(
    event_time        DateTime64(6, 'UTC')        DEFAULT now64(6),
    source_table      LowCardinality(String),
    workspace_id      String,
    project_id        String,                     -- empty for workspace-scoped source tables
    deleted_id        String,                     -- source row identifier; not a UUID for every source table
    deletion_reason   LowCardinality(String)
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/deletion_events_local', '{replica}')
PARTITION BY toYYYYMM(event_time)
ORDER BY (source_table, event_time, deleted_id)
TTL toDateTime(event_time + INTERVAL 2 YEAR);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local ON CLUSTER '{cluster}';
