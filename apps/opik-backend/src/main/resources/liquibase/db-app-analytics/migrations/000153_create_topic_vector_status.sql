--liquibase formatted sql
--changeset yaroslavb:000153_create_topic_vector_status
--comment: Create topic_vector_status, the traces that can never get a vector: one row per (trace, task, task_version) (OPIK-8981)

-- topic_vector_status holds, for every trace the embed service can never give a vector, why: embed_refused (the
-- service refused that input on its own) or invalid_vector (it answered a zero or non-finite vector). The same summary
-- gets the same answer, so the vectors tick's window read leaves these keys out, as it leaves out the keys with a
-- vector, and a page of them is read once. As ai_label_status does for ai-labels, every final outcome has a row.
--   * Key, engine, id_at and partition are topic_vectors' (000149), so the trace-delete cascade and retention delete
--     its rows with the trace's, through the same statements.
--   * A relabel with a higher task version is a new key, so its summary is embedded again.
--   * No TTL: a row lives exactly as long as its trace.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vector_status ON CLUSTER '{cluster}'
(
    workspace_id     String                                          CODEC(ZSTD(3)),
    project_id       FixedString(36)                                 CODEC(ZSTD(1)),
    task             LowCardinality(String)                          CODEC(ZSTD(1)),
    trace_id         FixedString(36)                                 CODEC(ZSTD(1)),
    trace_created_at DateTime64(6, 'UTC')                            CODEC(Delta, ZSTD(1)),
    id_at            DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    task_version     UInt32                                          CODEC(T64, ZSTD(1)),
    reason           LowCardinality(String)                          CODEC(ZSTD(1)),
    last_updated_at  DateTime64(6, 'UTC')   DEFAULT now64(6)         CODEC(Delta, ZSTD(1)),
    is_deleted       UInt8                  DEFAULT 0,
    INDEX idx_trace_created_at trace_created_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/topic_vector_status',
    '{replica}', last_updated_at, is_deleted)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, task, trace_id, task_version)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.topic_vector_status ON CLUSTER '{cluster}' SYNC;
