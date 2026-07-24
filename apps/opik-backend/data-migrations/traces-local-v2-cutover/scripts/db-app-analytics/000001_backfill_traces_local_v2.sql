-- runbook traces-local-v2-cutover — step 1 of 3: backfill (reference statement)
--
-- This file is the SINGLE source of the backfill INSERT; ../backfill.sh reads it, substitutes the ${...} placeholders
-- (database, window bounds, block size) and runs it once per time sub-window — so the script and this reference never
-- drift. Run the migration through backfill.sh, never this file by hand. WINDOW_LO/WINDOW_HI are a created_at half-open
-- range the driver picks so each INSERT stays under its --max-rows-per-insert bound (see README "Batching and throttling").
--
-- Slicing rationale (created_at, not id / not workspace), delta and replay design: see ../../README.md.
-- Notes on the statement:
--   * The SOURCE is sliced by created_at (immutable across upserts, backed by a minmax skip index). The DESTINATION's
--     id_at partition is derived from each row's id independently of the slice.
--   * end_time and ttft are the two denullified columns: coalesce them to their sentinels (epoch / NaN).
--   * is_deleted is omitted so the new column defaults to 0.
--   * apply_deleted_mask stays at its default 1, so rows already lightweight-deleted on the source are skipped.
--   * No explicit ORDER BY: omitted deliberately to avoid a full per-window sort (memory). A parallel SELECT gives no
--     output-order guarantee, so inserted blocks may span/interleave partitions; the destination ReplacingMergeTree
--     dedups regardless of insert order and background merges compact the parts. This is NOT a claim that rows arrive
--     in sort-key order — do not rely on it (see README "Why slice by created_at").
--   * SETTINGS max_insert_block_size bounds the rows per part-forming block; peak insert memory is a small multiple of
--     the smaller of that and min_insert_block_size_bytes (256 MB default), which dominates for wide trace rows.

INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 (
    id,
    workspace_id,
    project_id,
    name,
    start_time,
    end_time,
    input,
    output,
    metadata,
    tags,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    error_info,
    thread_id,
    visibility_mode,
    truncation_threshold,
    input_slim,
    output_slim,
    ttft,
    source,
    environment
)
SELECT
    id,
    workspace_id,
    project_id,
    name,
    start_time,
    coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
    input,
    output,
    metadata,
    tags,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    error_info,
    thread_id,
    visibility_mode,
    truncation_threshold,
    input_slim,
    output_slim,
    coalesce(ttft, toFloat64('nan')) AS ttft,
    source,
    environment
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
  AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
SETTINGS max_insert_block_size = ${MAX_INSERT_BLOCK_SIZE},
         log_comment = 'traces_local_v2_backfill:${WINDOW_LO}:${WINDOW_HI}';

-- Per-window reconciliation is automated by backfill.sh (uniqExact of the dedup key, aborting on > 0.01% divergence);
-- fidelity QA across the whole copy is 000005 via verify.sh. Rollback before the EXCHANGE: rollback.sh --stage A.
