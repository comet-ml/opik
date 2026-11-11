--liquibase formatted sql
--changeset yaroslavb:000149_create_trace_topics
--comment: Create trace_topics, the topic of each trace as the nightly run (and later the arrival path) assigned it (OPIK-8987)

-- trace_topics holds one row per trace and assignment: every nightly run inserts a row for every trace of its window,
-- and the latest row per trace wins. topic_id is a topics.id from MySQL, or the all-zeros id for Other. source says who
-- wrote the row: 'night' (the nightly run, run_id is its topic_runs.run_id) or 'arrival' (stage 3).
-- The engine follows traces_local_v2 (000101) and span_weeks (000132):
--   * last_updated_at is the ReplacingMergeTree version, set by ClickHouse at insert.
--   * is_deleted is the ReplacingMergeTree soft-delete marker. Nothing sets it today: deletes are lightweight DELETEs.
--     It is in the engine now because engine parameters cannot be added by ALTER.
--   * Readers dedupe explicitly (LIMIT 1 BY trace, latest last_updated_at, then run_id) and then drop a row
--     whose latest version has is_deleted = 1.
--   * source is in the sort key, after trace_id: a merge collapses night rows only with night rows and arrival rows
--     only with arrival rows. An arrival row written after a night (a relabel) so cannot merge away the night row a
--     read by run_id looks for. A trace keeps at most two rows after merges; the "latest per trace" readers dedupe by
--     trace, not by the sort key, so they read the same answer either way.
--   * id_at and the partition key are topic_vectors' (000148), so retention and the trace-delete cascade prune the same
--     weekly partitions as for the trace. No TTL: a row lives exactly as long as its trace.
--   * idx_trace_created_at: trace_created_at never changes for a key, so a minmax index on it is safe before dedup;
--     the topic counts read a window of trace_created_at.
-- Codecs follow topic_vectors (000148).
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_topics ON CLUSTER '{cluster}'
(
    workspace_id     String                                          CODEC(ZSTD(3)),
    project_id       FixedString(36)                                 CODEC(ZSTD(1)),
    trace_id         FixedString(36)                                 CODEC(ZSTD(1)),
    trace_created_at DateTime64(6, 'UTC')                            CODEC(Delta, ZSTD(1)),
    id_at            DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    topic_id         FixedString(36)                                 CODEC(ZSTD(1)),
    source           Enum8('night' = 1, 'arrival' = 2)               CODEC(ZSTD(1)),
    run_id           String                                          CODEC(ZSTD(1)),
    last_updated_at  DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    is_deleted       UInt8                  DEFAULT 0,
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/trace_topics',
    '{replica}', last_updated_at, is_deleted)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, trace_id, source)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_topics ON CLUSTER '{cluster}' SYNC;
