--liquibase formatted sql
--changeset boryst:000094_create_cipx_trace_identity
--comment: Dedicated cipx trace-identity table, derived from traces.metadata.cipx.session

-- cipx identity rides on the trace under cipx.session.identity, not on the span. The spend
-- queries that need it (the ?user_uuid= filter, the counts summary, and the users
-- leaderboard) read it from `traces` and JSON-parse it today. This table holds only cipx
-- traces with identity, pre-extracted into typed columns, and joins to cipx_spends on
-- trace_id. The default composition/breakdown path does not touch this table.
--
-- ReplacingMergeTree(last_updated_at): same at-least-once ingestion as cipx_spends; FINAL
-- collapses duplicate/replayed rows to one per sorting key, keeping the highest
-- last_updated_at. last_updated_at uses the column DEFAULT now64(6) (ingestion time),
-- mirroring `traces`, which likewise leaves it to the ClickHouse default. ORDER BY
-- mirrors `traces` (workspace_id, project_id, id): trace_id is the join key to cipx_spends
-- and is the unique dedup key. start_time stays a row-level filter, as in `traces`.
--
-- schema_version is stored so the existing `>= 2` gate is a cheap integer filter instead of
-- a JSON parse; keeping it (vs gating only at write time) lets the threshold move again
-- without rebuilding the table, as it already did from v1 to v2.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
(
    workspace_id      String,
    project_id        FixedString(36),
    trace_id          FixedString(36),
    start_time        DateTime64(9, 'UTC'),

    user_uuid         String,
    user_email        String,
    user_display_name String,
    repository        String,
    schema_version    Int32,

    last_updated_at   DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_trace_identities',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, trace_id);

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}';
