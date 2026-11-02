--liquibase formatted sql
--changeset yaroslavb:000144_create_trace_topics
--comment: Create trace_topics, the topic of each trace as the nightly run (and later the arrival path) assigned it (OPIK-8987)

-- trace_topics holds one row per trace and assignment: every nightly run inserts a row for every trace of its window,
-- and the latest row per trace wins (updated_at is the ReplacingMergeTree version, set by ClickHouse at insert).
-- topic_id is a topics.id from MySQL, or the all-zeros id for Other. source says who wrote the row: 'night' (the nightly
-- run, run_id is its topic_runs.run_id) or 'arrival' (stage 3). Readers dedupe explicitly (LIMIT 1 BY the sort key).
--   * id_at and the partition key are topic_vectors' (000143), so retention and the trace-delete cascade prune the same
--     weekly partitions as for the trace. No TTL: a row lives exactly as long as its trace.
--   * idx_trace_created_at: trace_created_at never changes for a key, so a minmax index on it is safe before dedup;
--     the topic counts read a window of trace_created_at.
-- Codecs follow topic_vectors (000143).
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
    updated_at       DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/trace_topics',
    '{replica}', updated_at)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, trace_id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.trace_topics ON CLUSTER '{cluster}' SYNC;
