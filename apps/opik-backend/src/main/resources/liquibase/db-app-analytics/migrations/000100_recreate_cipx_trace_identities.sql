--liquibase formatted sql
--changeset boryst:000100_recreate_cipx_trace_identities
--comment: Recreate cipx_trace_identities with user_uuid in the primary key

-- Retrieval now filters identities by user_uuid only (user_email is resolved to user_uuid via the
-- MySQL cipx_user_mappings table first), so user_uuid moves into the sorting key ahead of trace_id:
-- the ?user_uuid= drill-down and /me queries become a direct primary-key prefix lookup. trace_id
-- stays last to keep the key unique per trace (it is also the join/semijoin key to cipx_spends and
-- cipx_spend_blocks). user_email / user_display_name remain as data columns — the users leaderboard
-- returns them and the name search filters on them.
--
-- The feature is not in production; the table is dropped and recreated empty, and existing rows are
-- re-ingested through the new pipeline.
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' SYNC;

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
    session_id        String,
    schema_version    Int32,

    last_updated_at   DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/cipx_trace_identities_v2',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, project_id, user_uuid, trace_id);

--rollback empty
