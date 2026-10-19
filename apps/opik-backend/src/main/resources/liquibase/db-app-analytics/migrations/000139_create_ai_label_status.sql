--liquibase formatted sql
--changeset collinc:000139_create_ai_label_status
--comment: Create ai_label_status, the ai-labels outcomes: one row per (trace, task), the latest row authoritative

-- ai_label_status is the ai-labels outcome of each (trace, task). No row means it is not labeled yet (or is in flight,
-- which MySQL's ai_label_job_items tracks); otherwise its latest row (by updated_at) says whether it is done or failed.
-- Created on every install and left empty while aiLabels.enabled is off.
--   * updated_at is the ReplacingMergeTree version and is set by ClickHouse at insert, never by a client, so a later
--     write always wins. Readers still dedupe explicitly (LIMIT 1 BY the sort key), never relying on merges.
--   * id_at and the partition key are traces_local_v2's (000114), derived from trace_id, so every row of a trace sits in
--     its trace's weekly partition and retention's week predicate prunes it the same way.
--   * idx_trace_created_at: trace_created_at is copied from the trace and never changes for a (trace, task), so a
--     minmax index on it is safe before dedup; the sweep's horizon filter narrows on it.
--   * job_id is provenance only: the job that produced the row, '' for a failure before submit.
-- Codecs follow traces_local_v2: ZSTD(3) for workspace_id, ZSTD(1) for UUIDs and low-cardinality values, T64 + ZSTD(1)
-- for small integers, Delta + ZSTD(1) for timestamps.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.ai_label_status ON CLUSTER '{cluster}'
(
    workspace_id     String                                          CODEC(ZSTD(3)),
    project_id       FixedString(36)                                 CODEC(ZSTD(1)),
    task             LowCardinality(String)                          CODEC(ZSTD(1)),
    trace_id         FixedString(36)                                 CODEC(ZSTD(1)),
    trace_created_at DateTime64(6, 'UTC')                            CODEC(Delta, ZSTD(1)),
    id_at            DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    task_version     UInt32                                          CODEC(T64, ZSTD(1)),
    renderer_version UInt32                                          CODEC(T64, ZSTD(1)),
    status           Enum8('done' = 2, 'failed' = 3)                 CODEC(ZSTD(1)),
    retryable        Bool                                            CODEC(ZSTD(1)),
    attempt          UInt8                                           CODEC(T64, ZSTD(1)),
    error_code       LowCardinality(String) DEFAULT ''               CODEC(ZSTD(1)),
    job_id           String                 DEFAULT ''               CODEC(ZSTD(1)),
    compaction_level LowCardinality(String) DEFAULT ''               CODEC(ZSTD(1)),
    updated_at       DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/ai_label_status',
    '{replica}', updated_at)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, task, trace_id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.ai_label_status ON CLUSTER '{cluster}' SYNC;
