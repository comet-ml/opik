--liquibase formatted sql
--changeset andrescrz:000101_create_traces_local_v2_table
--comment: Create the traces_local_v2 table, a weekly-partitioned successor to traces, empty (no data cutover here)

-- traces_local_v2 is the target layout for `traces` under partitioned + tiered storage. It is created empty
-- next to the live `traces` table; populating it and swapping it in happen in later migrations. The schema
-- mirrors `traces` so a later `INSERT INTO traces_local_v2 SELECT ... FROM traces` maps by column name, with
-- these differences folded in:
--   * PARTITION BY toMonday(id_at) (weekly). id_at is derived from the row's UUIDv7 id at insert time, so it
--     is a deterministic function of the immutable id: a row always lands in the same weekly partition across
--     ReplacingMergeTree upserts.
--   * Timestamps stored at microsecond precision (DateTime64(6)); nothing ingested needs finer resolution.
--   * end_time, ttft and duration are non-Nullable, using epoch / NaN sentinels, dropping the per-column
--     null-mask overhead on hot reads.
--   * is_deleted is the ReplacingMergeTree delete meta-column, so a future delete path can upsert a tombstone
--     that merges away during compaction. It is unused for now.
-- Locked at creation (changing any of these later needs another full table rewrite, so they are fixed now):
--   * ORDER BY / implicit PRIMARY KEY (workspace_id, project_id, id): matches every hot query (workspace+project
--     filter, lookup by id) and is the ReplacingMergeTree dedup key; id stays in the key so point lookups keep
--     granule pruning (a shorter primary key would only shave a tiny in-memory index for a real lookup cost).
--   * PARTITION BY toMonday(id_at) (weekly, see above).
--   * ReplicatedReplacingMergeTree version (last_updated_at) + is_deleted meta-columns: the engine and its
--     parameters are immutable after creation.
--   * No SAMPLE BY, intentionally: sampling needs a hashed column in the sort key, which would break the id/time
--     ordering the dominant access path relies on; Opik analytics are workspace/project-scoped and exact, so
--     sampling would not earn back that cost. Everything else (codecs, TTL, skip indexes, storage policy) is
--     ALTER-able later, though re-compressing or materializing an index on the full table is a heavy mutation.
-- Compression (per ClickHouse codec guidance): timestamps use Delta + ZSTD(1) (monotonic, irregularly spaced);
-- the length counters use T64 + ZSTD(1) (narrow integers); UUID ids and large free-text use ZSTD(3) (compress
-- once, read many); other variable strings/arrays/floats use ZSTD(1) (the recommended general-purpose baseline).
-- Fixed-width low-entropy columns (the Enum8s, the UInt8 flag, the constant threshold, the LowCardinality
-- column and the run-length-friendly leading key workspace_id) are left on the server default, where LZ4 is
-- comparable and decompresses faster.
-- The engine uses its own ZooKeeper path ('.../traces_local_v2'): two replicated tables cannot share a replica
-- path, so it must differ from `traces` while both exist. A replica path is independent of the table name, so
-- it stays valid after a later rename/swap.
-- No storage_policy is set here; the tiered-storage policy is attached by a later, environment-gated migration,
-- keeping this DDL identical across all deployments.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}'
(
    id                   FixedString(36)        CODEC(ZSTD(3)),
    workspace_id         String,
    project_id           FixedString(36)        CODEC(ZSTD(3)),
    name                 String                 DEFAULT ''       CODEC(ZSTD(1)),
    start_time           DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    end_time             DateTime64(6, 'UTC')   DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta, ZSTD(1)),  -- epoch = not ended yet
    input                String                 DEFAULT ''       CODEC(ZSTD(3)),
    output               String                 DEFAULT ''       CODEC(ZSTD(3)),
    metadata             String                 DEFAULT ''       CODEC(ZSTD(3)),
    tags                 Array(String)          DEFAULT []       CODEC(ZSTD(1)),
    created_at           DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    last_updated_at      DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    created_by           String                 DEFAULT ''       CODEC(ZSTD(1)),
    last_updated_by      String                 DEFAULT ''       CODEC(ZSTD(1)),
    error_info           String                 DEFAULT ''       CODEC(ZSTD(1)),
    thread_id            String                 DEFAULT ''       CODEC(ZSTD(1)),
    visibility_mode      Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) DEFAULT 'default',
    truncation_threshold UInt64                 DEFAULT 10001,   -- 10 KB + 1 byte, threshold for the truncated_* columns
    input_slim           String                 DEFAULT ''       CODEC(ZSTD(3)),
    output_slim          String                 DEFAULT ''       CODEC(ZSTD(3)),
    -- NaN = not measured (0 is a valid value); CH has no nan() literal. ZSTD (not Gorilla/FPC, which need
    -- correlated float sequences) since the column is dominated by the repeated NaN sentinel.
    ttft                 Float64                DEFAULT toFloat64('nan') CODEC(ZSTD(1)),
    source               Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown',
    environment          LowCardinality(String) DEFAULT '',
    is_deleted           UInt8                  DEFAULT 0,
    input_length         UInt64  MATERIALIZED length(input)                                                                CODEC(T64, ZSTD(1)),
    output_length        UInt64  MATERIALIZED length(output)                                                               CODEC(T64, ZSTD(1)),
    metadata_length      UInt64  MATERIALIZED length(metadata)                                                             CODEC(T64, ZSTD(1)),
    truncated_input      String  MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input)    CODEC(ZSTD(3)),
    truncated_output     String  MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output) CODEC(ZSTD(3)),
    output_keys          Array(Tuple(key String, type String)) MATERIALIZED
        arrayMap(
            key -> tuple(key, toString(JSONType(JSONExtractRaw(output, key)))),
            JSONExtractKeys(output)
        ) CODEC(ZSTD(1)),
    -- NaN when end_time is the epoch sentinel (not ended) or start_time is the epoch (unset, as the live traces
    -- duration also guards); microsecond diff keeps sub-millisecond spans (0 is a legitimate duration).
    `duration`           Float64 MATERIALIZED
        if(end_time = toDateTime64('1970-01-01 00:00:00', 6) OR start_time = toDateTime64('1970-01-01 00:00:00', 6),
            toFloat64('nan'),
            dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1)),
    id_at                DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(Delta, ZSTD(1)),  -- partition input, see header
    -- Point lookups by id alone (project unknown) can't use the primary key, and retention/read paths also scan
    -- id-ranges (id >= .. AND id < ..). id is the sort-key suffix and a UUIDv7 (time-ordered), so values cluster
    -- within granules: minmax prunes both. Matches idx_spans_id.
    INDEX idx_traces_id id TYPE minmax GRANULARITY 1,
    INDEX idx_traces_id_at id_at TYPE minmax GRANULARITY 1,  -- granule-level pruning on id_at within a partition
    -- Carried over from traces so the successor keeps the same read performance.
    INDEX idx_traces_source source TYPE set(0) GRANULARITY 1,
    INDEX idx_traces_thread_id_bf thread_id TYPE bloom_filter(0.01) GRANULARITY 1,
    INDEX idx_traces_environment environment TYPE set(0) GRANULARITY 1,
    INDEX idx_traces_created_at created_at TYPE minmax GRANULARITY 1,
    INDEX idx_traces_last_updated_at last_updated_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/traces_local_v2',
    '{replica}',
    last_updated_at,
    is_deleted)
PARTITION BY toMonday(id_at)
ORDER BY (workspace_id, project_id, id)
-- ~40 MiB per granule so a granule fills toward the 8192-row target on these wide rows, making skip indexes prune effectively.
SETTINGS index_granularity = 8192, index_granularity_bytes = 41943040;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';

