--liquibase formatted sql
--changeset yaroslavb:000149_create_topic_vectors
--comment: Create topic_vectors, the bge-small vector of each labeled trace's intent: one row per (trace, task, task_version) (OPIK-8981)

-- topic_vectors holds, for every trace with a done trace_summary in ai_label_status, the vector of its summary text as
-- opik-inference /v1/embed returns it, scaled to unit length, next to the text itself. The Topics nightly job reads
-- text and vector together, so the row is self-contained. Created on every install and left empty while
-- topics.enabled is off.
--   * One row per (trace, task, task_version): a relabel with a higher task version adds a row and keeps the old one.
--     task is the ai_labels task whose text was embedded, as in ai_label_status. Topics embeds one task at a time,
--     and every reader filters on task = TopicEmbedding.SUMMARY_TASK, so a second embedded task gets its own rows
--     without changing the readers.
--     embed_model and embed_dimensions say which model made the vector, so a model change can be re-made from ai_labels.
--   * The model is traces_local_v2 (000114): the engine's version (last_updated_at) and soft-delete marker
--     (is_deleted), id_at, the partition key and the codecs are its.
--   * last_updated_at is set by ClickHouse at insert. A rewrite of the same key is the same vector, so which row a
--     merge keeps does not matter; readers still dedupe explicitly (LIMIT 1 BY the sort key, latest first).
--   * is_deleted: deletes are lightweight DELETEs today, as on traces_local_v2. Tombstone rows (is_deleted = 1) are the
--     future delete path, and every reader already drops a key whose latest row is a tombstone, after the dedup. The
--     marker is in the engine now because engine parameters cannot be added by ALTER.
--   * ai_label_status (000139) has the same id_at and partition key, so retention and the trace-delete cascade prune
--     the same weekly partitions as for the trace. No TTL: a vector lives exactly as long as its trace.
--   * idx_trace_created_at: trace_created_at is copied from the trace and never changes for a key, so a minmax index on
--     it is safe before dedup; the nightly job reads a window of trace_created_at.
--   * The trade-off of id_at partitions: retention and the cascade stay partition-scoped, but a read by
--     trace_created_at window cannot prune partitions. It opens every weekly partition for index analysis and reads
--     only the granules idx_trace_created_at keeps.
--   * index_granularity_bytes stays at the 10 MiB default: with BFloat16 a row is about 1 KiB (768 bytes of vector
--     and a short text), so 8192 rows fit. A longer text only makes adaptive granules a little smaller.
--   * The vector is BFloat16, half the bytes of Float32, with ZSTD(1): repeated intents give repeated vectors, and the
--     sign and exponent byte repeats, so ZSTD(1) is clearly smaller than LZ4 at a small decode cost.
--     ClickHouse truncates Float32 to BFloat16 on insert, so a stored vector is slightly shorter than unit length.
--     Readers select CAST(vector AS Array(Float32)), as the r2dbc driver returns BFloat16 as raw 16-bit values, and
--     scale each vector back to unit length.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vectors ON CLUSTER '{cluster}'
(
    workspace_id     String                                          CODEC(ZSTD(3)),
    project_id       FixedString(36)                                 CODEC(ZSTD(1)),
    task             LowCardinality(String)                          CODEC(ZSTD(1)),
    trace_id         FixedString(36)                                 CODEC(ZSTD(1)),
    trace_created_at DateTime64(6, 'UTC')                            CODEC(Delta, ZSTD(1)),
    id_at            DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    task_version     UInt32                                          CODEC(T64, ZSTD(1)),
    embed_model      LowCardinality(String)                          CODEC(ZSTD(1)),
    embed_dimensions UInt16                                          CODEC(T64, ZSTD(1)),
    text             String                                          CODEC(ZSTD(3)),
    vector           Array(BFloat16)                                 CODEC(ZSTD(1)),
    last_updated_at  DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    is_deleted       UInt8                  DEFAULT 0,
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/topic_vectors',
    '{replica}', last_updated_at, is_deleted)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, task, trace_id, task_version)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vectors ON CLUSTER '{cluster}' SYNC;
