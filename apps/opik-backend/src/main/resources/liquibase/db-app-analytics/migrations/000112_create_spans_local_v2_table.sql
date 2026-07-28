--liquibase formatted sql
--changeset thiagohora:000112_create_spans_local_v2_table
--comment: Create the spans_local_v2 table, a weekly-partitioned successor to spans, empty (no data cutover here)

-- spans_local_v2 is the target layout for `spans` under partitioned + tiered storage, the counterpart of
-- 000101 (traces_local_v2). It is created empty next to the live `spans` table; populating it and swapping it in
-- happen in later migrations. The schema mirrors `spans` so a later `INSERT INTO spans_local_v2 SELECT ... FROM spans`
-- maps by column name, with these differences folded in:
--   * PARTITION BY toMonday(id_at) (weekly). id_at is derived from the row's UUIDv7 id at insert time, so it
--     is a deterministic function of the immutable id: a row always lands in the same weekly partition across
--     ReplacingMergeTree upserts.
--   * Timestamps stored at microsecond precision (DateTime64(6)); nothing ingested needs finer resolution. This
--     narrows start_time/end_time/created_at from the DateTime64(9) the live table uses (last_updated_at is already
--     microseconds); no ingestion path produces sub-microsecond values, so the cast is lossless in practice.
--   * end_time, ttft and duration are non-Nullable, using epoch / NaN sentinels, dropping the per-column
--     null-mask overhead on hot reads. Same three columns as traces_local_v2, and the same sentinels, so
--     SentinelTranslation covers both tables.
--   * parent_span_id is FixedString(36) instead of String: it holds a UUID or nothing, so the fixed width removes the
--     per-value offset and compresses like the other id columns. Absent is the empty sentinel, which ClickHouse stores
--     as 36 NUL bytes. Two consequences for the cutover, both verified against this table on 26.3:
--       - SQL is unaffected. ClickHouse trims the padding when casting FixedString to String, so the DAO's presence
--         checks — LENGTH(CAST(parent_span_id AS Nullable(String))) > 0 — still read 0 for the sentinel and keep
--         behaving exactly as they do on the String column. A bare LENGTH(parent_span_id) would instead always be 36,
--         so the cast must stay.
--       - The read path is not. The driver surfaces the padded form to Java as 36 NUL characters, which is not blank
--         (NUL is not whitespace), so a !isBlank() guard lets it through and UUID.fromString then throws. Reads must go
--         through SentinelTranslation.emptyUuidToNull, which recognises both the empty and the all-NUL form.
--   * usage is Map(String, Int64) instead of Map(String, Int32): a lossless widening that matches what the DAO already
--     binds (SpanDAO casts to Map(String, Int64) on update, and the API model carries the counts as boxed integers),
--     removing the narrowing the live column forces on every write.
--   * is_deleted is the ReplacingMergeTree delete meta-column, so a future delete path can upsert a tombstone
--     that merges away during compaction. It is unused for now.
-- No output_keys column: it is a traces-only column (added by 000044 for the trace output-key filters) and has no
-- counterpart on spans, so there is nothing for the cutover INSERT to map.
-- Locked at creation (changing any of these later needs another full table rewrite, so they are fixed now):
--   * ORDER BY / implicit PRIMARY KEY (workspace_id, project_id, trace_id, parent_span_id, id): unchanged from the live
--     `spans` table. It matches every hot query (workspace+project filter, then the spans of one trace, then the
--     children of one span) and is the ReplacingMergeTree dedup key, which must stay identical for the cutover to
--     dedup the same rows the live table does.
--   * PARTITION BY toMonday(id_at) (weekly, see above).
--   * ReplicatedReplacingMergeTree version (last_updated_at) + is_deleted meta-columns: the engine and its
--     parameters are immutable after creation. The live table has no is_deleted, so this is the one engine-parameter
--     difference; adding it now avoids a second rewrite when the delete path lands.
--   * No SAMPLE BY, intentionally: sampling needs a hashed column in the sort key, which would break the
--     trace/parent/id ordering the dominant access path relies on; Opik analytics are workspace/project-scoped and
--     exact, so sampling would not earn back that cost. Everything else (codecs, TTL, skip indexes, storage policy)
--     is ALTER-able later, though re-compressing or materializing an index on the full table is a heavy mutation.
-- Compression: the codecs start from the set the traces benchmark settled on (000101 refined by 000106 and 000107),
-- so this table skips the three refinement passes traces needed:
--   * FixedString(36) UUIDv7 ids (id, project_id, trace_id, parent_span_id): ZSTD(1). Higher levels did not improve
--     (and slightly hurt) the ratio, and they are immune to the 26.3 regression below.
--   * Small, repetitive, variable-length String/Array (workspace_id, name, tags, created_by, last_updated_by, and the
--     spans-only model, provider and total_estimated_cost_version): ZSTD(3). ClickHouse 26.3 (the LTS this table is
--     deployed on) regressed ZSTD level 1 on exactly this shape, making ZSTD(1) larger than the LZ4 default; ZSTD(3)
--     is unaffected and smallest on both 25.8 and 26.3, at codec-level-independent decode (no read penalty).
--   * Long/structured text (input, output, metadata and their slim/truncated forms): ZSTD(3).
--   * Timestamps (start_time, end_time, created_at, last_updated_at, id_at): Delta + ZSTD(1). Monotonic enough in the
--     storage order on real data for Delta to win, including end_time and last_updated_at (a synthetic slice had
--     suggested otherwise for those two; 000107 reverted that on real traces data).
--   * The *_length counters: T64 + ZSTD(1) (narrow integers).
--   * ttft and duration: ZSTD(1). They are not correlated float series, so Gorilla/FPC never help regardless of how
--     much of the column is the NaN sentinel.
--   * Enum8 (type, source), LowCardinality (environment) and the constant truncation_threshold: ZSTD(1), which
--     compresses ~2x better than the LZ4 default on these tiny columns at equal decode cost.
--   * is_deleted: server default, matching traces_local_v2.
-- Of the spans-only columns, the four with no traces analogue of the same shape fall out of the rules above: the
-- FixedString(36) ids (trace_id, parent_span_id) take the id codec, the Enum8 type takes the Enum8 codec, and
-- model/provider/total_estimated_cost_version take the 26.3-safe ZSTD(3) for small repetitive text. That leaves
-- usage (Map(String, Int64), a composite of repetitive string keys and narrow-integer values, which no single-column
-- rule covers) and total_estimated_cost (Decimal128) on ZSTD(1) as best guesses, for the spans codec benchmark to
-- settle against real spans data.
-- The engine uses its own ZooKeeper path ('.../spans_local_v2'): two replicated tables cannot share a replica
-- path, so it must differ from `spans` while both exist. A replica path is independent of the table name, so
-- it stays valid after a later rename/swap.
-- No storage_policy is set here; the tiered-storage policy is attached by a later, environment-gated migration,
-- keeping this DDL identical across all deployments.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}'
(
    id                           FixedString(36)        CODEC(ZSTD(1)),
    workspace_id                 String                 CODEC(ZSTD(3)),
    project_id                   FixedString(36)        CODEC(ZSTD(1)),
    trace_id                     FixedString(36)        CODEC(ZSTD(1)),
    parent_span_id               FixedString(36)        DEFAULT ''       CODEC(ZSTD(1)),  -- empty = root span, see header
    name                         String                 DEFAULT ''       CODEC(ZSTD(3)),
    type                         Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4) DEFAULT 'unknown' CODEC(ZSTD(1)),
    start_time                   DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    end_time                     DateTime64(6, 'UTC')   DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta, ZSTD(1)),  -- epoch = not ended yet
    input                        String                 DEFAULT ''       CODEC(ZSTD(3)),
    output                       String                 DEFAULT ''       CODEC(ZSTD(3)),
    metadata                     String                 DEFAULT ''       CODEC(ZSTD(3)),
    tags                         Array(String)          DEFAULT []       CODEC(ZSTD(3)),
    usage                        Map(String, Int64)     DEFAULT map()    CODEC(ZSTD(1)),
    created_at                   DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    last_updated_at              DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    created_by                   String                 DEFAULT ''       CODEC(ZSTD(3)),
    last_updated_by              String                 DEFAULT ''       CODEC(ZSTD(3)),
    model                        String                 DEFAULT ''       CODEC(ZSTD(3)),
    provider                     String                 DEFAULT ''       CODEC(ZSTD(3)),
    total_estimated_cost         Decimal(38, 12)        DEFAULT 0        CODEC(ZSTD(1)),
    total_estimated_cost_version String                 DEFAULT ''       CODEC(ZSTD(3)),
    error_info                   String                 DEFAULT ''       CODEC(ZSTD(1)),
    truncation_threshold         UInt64                 DEFAULT 10001    CODEC(ZSTD(1)),   -- 10 KB + 1 byte, threshold for the truncated_* columns
    input_slim                   String                 DEFAULT ''       CODEC(ZSTD(3)),
    output_slim                  String                 DEFAULT ''       CODEC(ZSTD(3)),
    -- NaN = not measured (0 is a valid value); CH has no nan() literal. ZSTD (not Gorilla/FPC, which need
    -- correlated float sequences) since the column is dominated by the repeated NaN sentinel.
    ttft                         Float64                DEFAULT toFloat64('nan') CODEC(ZSTD(1)),
    source                       Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown' CODEC(ZSTD(1)),
    environment                  LowCardinality(String) DEFAULT ''       CODEC(ZSTD(1)),
    is_deleted                   UInt8                  DEFAULT 0,
    input_length                 UInt64  MATERIALIZED length(input)                                                                CODEC(T64, ZSTD(1)),
    output_length                UInt64  MATERIALIZED length(output)                                                               CODEC(T64, ZSTD(1)),
    metadata_length              UInt64  MATERIALIZED length(metadata)                                                             CODEC(T64, ZSTD(1)),
    truncated_input              String  MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input)    CODEC(ZSTD(3)),
    truncated_output             String  MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output) CODEC(ZSTD(3)),
    -- NaN when end_time is the epoch sentinel (not ended) or start_time is the epoch (unset, as the live spans
    -- duration also guards); microsecond diff keeps sub-millisecond spans (0 is a legitimate duration).
    `duration`                   Float64 MATERIALIZED
        if(end_time = toDateTime64('1970-01-01 00:00:00', 6) OR start_time = toDateTime64('1970-01-01 00:00:00', 6),
            toFloat64('nan'),
            dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1)),
    id_at                        DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(Delta, ZSTD(1)),  -- partition input, see header
    -- id-only lookups (project and trace unknown) can't use the primary key, where id is the fifth column.
    -- Carried over from `spans`: minmax prunes the id-range predicates the retention/read paths use (id >= .. AND id < ..).
    INDEX idx_spans_id id TYPE minmax GRANULARITY 1,
    INDEX idx_spans_id_at id_at TYPE minmax GRANULARITY 1,  -- granule-level pruning on id_at within a partition
    -- Carried over from `spans` so the successor keeps the same read performance.
    INDEX idx_spans_source source TYPE set(0) GRANULARITY 1,
    INDEX idx_spans_environment environment TYPE set(0) GRANULARITY 1,
    INDEX idx_spans_created_at created_at TYPE minmax GRANULARITY 1,
    INDEX idx_spans_last_updated_at last_updated_at TYPE minmax GRANULARITY 1
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/spans_local_v2',
    '{replica}',
    last_updated_at,
    is_deleted)
PARTITION BY toMonday(id_at)
ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id)
-- ~40 MiB per granule so a granule fills toward the 8192-row target on these wide rows, making skip indexes prune effectively.
SETTINGS index_granularity = 8192, index_granularity_bytes = 41943040;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}';

