--liquibase formatted sql
--changeset andrescrz:000105_apply_traces_local_v2_codec_refinements
--comment: Apply the OPIK-6899 benchmark-driven codec refinements to traces_local_v2 (metadata-only on the empty table)

-- Six per-column codec refinements the OPIK-6899 benchmark found to improve on the provisional codecs shipped in 000101.
-- traces_local_v2 is still empty (pre-cutover), so each MODIFY COLUMN is a metadata-only change with no data to
-- re-compress; it must therefore run before any backfill populates the table, or it degrades into a full re-compress +
-- output_keys recompute mutation. They are best guesses on a synthetic production-shaped slice, to be re-validated
-- against real data at the staging cutover; none is ever worse than the 000101 codec, so adopting now is low-regret.
--   * end_time, last_updated_at: drop Delta. Unlike start_time/created_at (monotonic in the (workspace_id, project_id,
--     id) storage order), these are NOT monotonic — end_time is start_time + a variable duration (plus epoch sentinels
--     for unfinished traces), and last_updated_at is the ReplacingMergeTree version column's last-write value — so Delta
--     hurts and plain ZSTD(1) is ~11% smaller.
--   * visibility_mode, source (Enum8) and environment (LowCardinality): ZSTD(1) instead of the server default. On these
--     tiny columns ZSTD(1) compresses ~2x better than the LZ4 default at equal decode cost (decode is dominated by
--     fixed per-query overhead, not the codec), refining the "LZ4 is comparable" rationale in 000101.
--   * output_keys: ZSTD(3) instead of ZSTD(1). The (json key, json type) tuples are structured text like input/output/
--     metadata, so ZSTD(3) is ~16% smaller at level-independent decode — unlike the low-cardinality tags array.
-- Not changed by this migration, but recorded here because 000101 is immutable: 000101 justifies ttft's ZSTD(1) as the
-- column being "dominated by the repeated NaN sentinel". Per the benchmark that is only a partial reason — ttft and the
-- materialized duration are not correlated float series, so Gorilla/FPC never help regardless of the NaN fraction, and
-- ZSTD(1) is correct either way. Evidence: TracesLocalV2BenchmarkTest#floatColumnsUseZstd1RegardlessOfNaNFraction.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS end_time          DateTime64(6, 'UTC')   DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS last_updated_at   DateTime64(6, 'UTC')   DEFAULT now64(6) CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS visibility_mode   Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) DEFAULT 'default' CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS source            Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5)
        DEFAULT 'unknown' CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS environment       LowCardinality(String) DEFAULT '' CODEC(ZSTD(1)),
    MODIFY COLUMN IF EXISTS output_keys       Array(Tuple(key String, type String)) MATERIALIZED
        arrayMap(key -> tuple(key, toString(JSONType(JSONExtractRaw(output, key)))), JSONExtractKeys(output)) CODEC(ZSTD(3));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' MODIFY COLUMN IF EXISTS end_time DateTime64(6, 'UTC') DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta, ZSTD(1)), MODIFY COLUMN IF EXISTS last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6) CODEC(Delta, ZSTD(1)), MODIFY COLUMN IF EXISTS visibility_mode Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) DEFAULT 'default', MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown', MODIFY COLUMN IF EXISTS environment LowCardinality(String) DEFAULT '', MODIFY COLUMN IF EXISTS output_keys Array(Tuple(key String, type String)) MATERIALIZED arrayMap(key -> tuple(key, toString(JSONType(JSONExtractRaw(output, key)))), JSONExtractKeys(output)) CODEC(ZSTD(1));

