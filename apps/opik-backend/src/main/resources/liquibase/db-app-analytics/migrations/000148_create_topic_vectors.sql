--liquibase formatted sql
--changeset yaroslavb:000143_create_topic_vectors
--comment: Create topic_vectors, the bge-small vector of each labeled trace's intent: one row per (trace, task_version) (OPIK-8981)

-- topic_vectors holds, for every trace with a done trace_summary in ai_label_status, the vector of its summary text as
-- opik-inference /v1/embed returns it, scaled to unit length, next to the text itself. The Topics nightly job reads
-- text and vector together, so the row is self-contained. Created on every install and left empty while
-- topics.enabled is off.
--   * One row per (trace, task_version): a relabel with a higher task version adds a row and keeps the old one.
--     embed_model and embed_dimensions say which model made the vector, so a model change can be re-made from ai_labels.
--   * updated_at is the ReplacingMergeTree version and is set by ClickHouse at insert. A rewrite of the same key is the
--     same vector, so which row a merge keeps does not matter; readers still dedupe explicitly (LIMIT 1 BY the sort key).
--   * id_at and the partition key are ai_label_status's (000139), so retention and the trace-delete cascade prune the
--     same weekly partitions as for the trace. No TTL: a vector lives exactly as long as its trace.
--   * idx_trace_created_at: trace_created_at is copied from the trace and never changes for a key, so a minmax index on
--     it is safe before dedup; the nightly job reads a window of trace_created_at.
-- Codecs follow ai_label_status (000139); the vector is plain ZSTD(1), as float noise compresses little either way.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vectors ON CLUSTER '{cluster}'
(
    workspace_id     String                                          CODEC(ZSTD(3)),
    project_id       FixedString(36)                                 CODEC(ZSTD(1)),
    trace_id         FixedString(36)                                 CODEC(ZSTD(1)),
    trace_created_at DateTime64(6, 'UTC')                            CODEC(Delta, ZSTD(1)),
    id_at            DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    task_version     UInt32                                          CODEC(T64, ZSTD(1)),
    embed_model      LowCardinality(String)                          CODEC(ZSTD(1)),
    embed_dimensions UInt16                                          CODEC(T64, ZSTD(1)),
    text             String                                          CODEC(ZSTD(3)),
    vector           Array(Float32)                                  CODEC(ZSTD(1)),
    updated_at       DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/topic_vectors',
    '{replica}', updated_at)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, trace_id, task_version)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vectors ON CLUSTER '{cluster}' SYNC;
