--liquibase formatted sql
--changeset andrescrz:000114_recreate_traces_local_v2_id_at_datetime64
--comment: Recreate traces_local_v2 with DateTime64(0) id_at and an honest, non-wrapping weekly partition (OPIK-7456)

-- Recreate traces_local_v2 with id_at as DateTime64(0) and an honest, non-wrapping weekly partition (OPIK-7456).
--
-- 000101 declared id_at as a 32-bit DateTime (max 2106): UUIDv7ToDateTime of a bad-id timestamp beyond 2106 (litellm
-- BerriAI/litellm#31294 mints ~2201) overflowed into a plausible-looking recent year. Those rows are legitimate,
-- customer-facing data — a valid UUIDv7 that merely carries a future embedded timestamp — so they must land in their own
-- honest weekly partition, never mixed with a real recent week (which would defeat per-partition DROP / retention /
-- tiering). id_at only ever needs second resolution — it is the weekly-partition input and the far-future audit anchor —
-- so DateTime64(0) is the minimal fix: it makes id_at honest to 2299 (the id_at > now() audit no longer misses ids that,
-- as a 32-bit DateTime, wrapped into a past year) and compresses best.
--
-- PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))): toMonday / toStartOfWeek return a
-- 16-bit Date (1970..2149) that wraps at BOTH ends — a far-future id (litellm ~2201) forward into a plausible recent
-- week, and an epoch id_at backward to ~2149 (UUIDv7ToDateTime returns 1970-01-01 for a non-v7 id — a v4/nil UUID, no
-- throw — whose ISO Monday 1969-12-29 underflows the Date min; non-v7 ids are commoner than litellm ones). toMonday only
-- stops wrapping under the global enable_extended_results_for_datetime_functions setting, which also changes
-- toStartOfInterval and breaks the metrics API, so it is not usable. This Date32 arithmetic (the Monday is the day minus
-- its 0-based weekday, entirely in Date32, range 1900..2299) equals toMonday across the in-range calendar except the four
-- epoch-week days, where it is more correct (1969-12-29 vs toMonday's 2149-06-04 underflow); it never wraps — out-of-range
-- ids saturate (a beyond-2299 id lands in 22991225, not a real year) — needs no setting, and via toYYYYMMDD is a UInt32
-- so the partition id stays a human-readable YYYYMMDD like 20250303 (legible in ZooKeeper paths, part directory names and
-- system.parts). The expression is pinned by TracesLocalV2PartitioningTest. The unchanged toMonday(id_at) read/retention
-- predicates still prune: each is paired with an authoritative id-range and prunes parts via the id_at minmax ClickHouse
-- keeps for the partition-key columns, independent of the key expression itself.
-- (toMonday(id_at) still wraps for an out-of-range id_at — far-future, or epoch from a non-v7 id — so a toMonday window
-- derived from an id SET spanning normal and wrapped can invert and drop rows; the read/delete layer covers that with
-- id-range-authoritative filters plus an unbounded fallback, OPIK-7483, and a new such query site needs the same.)
--
-- id_at is the PARTITION BY input, and ClickHouse forbids ALTER of a key column, so the table is dropped and recreated.
-- traces_local_v2 is empty in every install (the cutover that populates it runs manually, after this migration, and
-- renames it away). The DROP is IF EMPTY so ClickHouse refuses it — rather than silently dropping data — if the table is
-- ever unexpectedly non-empty (row-exact; max_table_size_to_drop = 0 disables the byte-size guard, matching 000023 /
-- 000039). Like the byte-size guard it replaces, IF EMPTY is a per-replica check — which suffices here because the table
-- is empty on every replica pre-cutover; the cluster-aware live-empty check (max rows over clusterAllReplicas) is
-- finalize.sh's job at the irreversible swap, not this metadata recreate's. The DROP is SYNC so the ZooKeeper replica
-- path is released before the recreate reuses it.
--
-- Every other column matches 000101 with the 000106 + 000107 codec refinements folded in (they ALTERed the old table and
-- do not re-run here); the set is pinned by TracesLocalV2BenchmarkTest#everyTracesLocalV2ColumnUsesItsIntendedCodec.
DROP TABLE IF EXISTS IF EMPTY ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0;

CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}'
(
    id                   FixedString(36)        CODEC(ZSTD(1)),
    workspace_id         String                 CODEC(ZSTD(3)),
    project_id           FixedString(36)        CODEC(ZSTD(1)),
    name                 String                 DEFAULT ''       CODEC(ZSTD(3)),
    start_time           DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    end_time             DateTime64(6, 'UTC')   DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta, ZSTD(1)),  -- epoch = not ended yet
    input                String                 DEFAULT ''       CODEC(ZSTD(3)),
    output               String                 DEFAULT ''       CODEC(ZSTD(3)),
    metadata             String                 DEFAULT ''       CODEC(ZSTD(3)),
    tags                 Array(String)          DEFAULT []       CODEC(ZSTD(3)),
    created_at           DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    last_updated_at      DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(Delta, ZSTD(1)),
    created_by           String                 DEFAULT ''       CODEC(ZSTD(3)),
    last_updated_by      String                 DEFAULT ''       CODEC(ZSTD(3)),
    error_info           String                 DEFAULT ''       CODEC(ZSTD(1)),
    thread_id            String                 DEFAULT ''       CODEC(ZSTD(3)),
    visibility_mode      Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) DEFAULT 'default' CODEC(ZSTD(1)),
    truncation_threshold UInt64                 DEFAULT 10001    CODEC(ZSTD(1)),   -- 10 KB + 1 byte, threshold for the truncated_* columns
    input_slim           String                 DEFAULT ''       CODEC(ZSTD(3)),
    output_slim          String                 DEFAULT ''       CODEC(ZSTD(3)),
    -- NaN = not measured (0 is a valid value); CH has no nan() literal. ZSTD (not Gorilla/FPC, which need a correlated
    -- float series) since ttft is not correlated, so those codecs never help.
    ttft                 Float64                DEFAULT toFloat64('nan') CODEC(ZSTD(1)),
    source               Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5)
        DEFAULT 'unknown' CODEC(ZSTD(1)),
    environment          LowCardinality(String) DEFAULT ''       CODEC(ZSTD(1)),
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
        ) CODEC(ZSTD(3)),
    -- NaN when end_time is the epoch sentinel (not ended) or start_time is the epoch (unset); microsecond diff keeps
    -- sub-millisecond spans (0 is a legitimate duration).
    `duration`           Float64 MATERIALIZED
        if(end_time = toDateTime64('1970-01-01 00:00:00', 6) OR start_time = toDateTime64('1970-01-01 00:00:00', 6),
            toFloat64('nan'),
            dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1)),
    -- DateTime64(0): widens the 32-bit DateTime 000101 used (which overflowed past 2106 — see header) while keeping the
    -- second resolution id_at needs as the partition/audit anchor. UUIDv7ToDateTime returns DateTime64(3); truncating to
    -- seconds drops nothing this column uses and compresses best (fewest significant digits). Codec unchanged (Delta+ZSTD1).
    id_at                DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(Delta, ZSTD(1)),
    -- id-only lookups (project unknown) can't use the primary key. Two complementary skip indexes on the UUIDv7 id:
    -- minmax for the id-range predicates retention/read paths use (id >= .. AND id < ..), and a bloom filter for the
    -- exact-match / IN lookups (getPartialById, findByIds, ...), which minmax can't prune within a week's shared prefix.
    INDEX idx_traces_id_minmax id TYPE minmax GRANULARITY 1,
    INDEX idx_traces_id_bf id TYPE bloom_filter(0.01) GRANULARITY 1,
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
PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
ORDER BY (workspace_id, project_id, id)
-- ~40 MiB per granule so a granule fills toward the 8192-row target on these wide rows, making skip indexes prune effectively.
SETTINGS index_granularity = 8192, index_granularity_bytes = 41943040;

-- Empty rollback: the recreate destroys the original 000101 table, so there is no clean inverse, and the empty
-- DateTime64 successor is compatible with every consumer (matches 000106/000107). traces_local_v2 is empty pre-cutover,
-- so nothing is lost; post-cutover the table has been renamed away and this changeset no longer applies.
--rollback empty

