--liquibase formatted sql
--changeset collinc:000140_create_ai_labels
--comment: Create ai_labels, the ai-labels values: one row per (trace, task, key), typed so values are queryable without parsing

-- ai_labels holds the AI-generated labels. key is a question id (without its task prefix), or 'output' for a generate
-- task; only the columns of a row's answer_type are set. Created on every install and left empty while
-- aiLabels.enabled is off.
--   * Written before the status row that marks the (trace, task) done, so a done status guarantees its values exist.
--   * updated_at is the ReplacingMergeTree version and is set by ClickHouse at insert, never by a client. Readers dedupe
--     explicitly (LIMIT 1 BY the sort key) and take each key's latest task_version.
--   * id_at and the partition key are traces_local_v2's (000114), derived from trace_id, so labels follow their
--     trace's weekly partition and retention's week predicate prunes them the same way.
-- Codecs follow traces_local_v2 (see 000139_create_ai_label_status).
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.ai_labels ON CLUSTER '{cluster}'
(
    workspace_id  String                                                          CODEC(ZSTD(3)),
    project_id    FixedString(36)                                                 CODEC(ZSTD(1)),
    task          LowCardinality(String)                                          CODEC(ZSTD(1)),
    trace_id      FixedString(36)                                                 CODEC(ZSTD(1)),
    key           String                                                          CODEC(ZSTD(3)),
    id_at         DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(trace_id)) CODEC(Delta, ZSTD(1)),
    task_version  UInt32                                                          CODEC(T64, ZSTD(1)),
    answer_type   Enum8('noul' = 1, 'choice' = 2, 'score' = 3, 'output' = 4)      CODEC(ZSTD(1)),
    noul          Nullable(Float32)                                               CODEC(ZSTD(1)),  -- P(true)
    score         Nullable(Float32)                                               CODEC(ZSTD(1)),  -- expected level
    choice        Nullable(String)                                                CODEC(ZSTD(3)),
    confidence    Nullable(Float32)                                               CODEC(ZSTD(1)),
    probabilities Map(String, Float32)                                            CODEC(ZSTD(3)),  -- empty for noul and output
    legend        Map(String, String)                                             CODEC(ZSTD(3)),  -- score levels; empty otherwise
    output        String               DEFAULT ''                                 CODEC(ZSTD(3)),  -- generate output, as JSON
    updated_at    DateTime64(6, 'UTC') DEFAULT now64(6)                           CODEC(Delta, ZSTD(1))
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/ai_labels',
    '{replica}', updated_at)
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, task, trace_id, key)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.ai_labels ON CLUSTER '{cluster}' SYNC;

