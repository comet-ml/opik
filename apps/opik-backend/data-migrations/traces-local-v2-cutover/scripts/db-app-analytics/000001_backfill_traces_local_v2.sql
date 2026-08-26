-- runbook traces-local-v2-cutover — step 1 of 3: backfill (reference statement)
-- The gate test TracesLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- This file is the SINGLE source of the backfill INSERT; ../backfill.sh reads it, substitutes the ${...} placeholders
-- and runs it once per time sub-window — so the script and this reference never
-- drift. Run the migration through backfill.sh, never this file by hand. WINDOW_LO/WINDOW_HI are a created_at half-open
-- range the driver picks so each INSERT stays under its --max-rows-per-insert bound (see README "Batching and throttling").
--
-- ALL FIVE placeholders the driver substitutes, so a new one is never missed here (an unsubstituted ${...} reaches the
-- server as a literal and the INSERT fails):
--   ${ANALYTICS_DB_DATABASE_NAME}          the analytics database
--   ${WINDOW_LO} / ${WINDOW_HI}            the created_at half-open window bounds
--   ${MAX_INSERT_BLOCK_SIZE}               rows per part-forming block
--   ${MAX_PARTITIONS_PER_INSERT_BLOCK}     partitions one block may span (required; see the note below)
--   ${MAX_INSERT_THREADS}                  threads for the INSERT SELECT pipeline. The driver OMITS this whole
--                                          SETTINGS line when --max-insert-threads is unset, so the server's
--                                          value is inherited; an explicit 0 forces no parallel execution.
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
--   * SETTINGS max_insert_threads sizes the INSERT SELECT pipeline. The driver omits this line when
--     --max-insert-threads is unset, so the server's configured value applies; an explicit 0 forces no parallel
--     execution. Raising it parallelises the insert side, which here carries the per-row cost of materialising
--     output_keys (a JSON parse of `output`). It has no effect unless the SELECT side is also parallel; see
--     max_threads. Two costs: peak memory rises, and on this table that compounds with oversized `output`
--     documents, so raise max_memory_usage alongside it; and each thread writes its own parts, so parts per
--     partition grow -- compare against parts_to_throw_insert and parts_to_delay_insert as read from
--     system.merge_tree_settings. For how to size the value, and for the measurements behind it, see
--     backfill.sh's --max-insert-threads option docs.
--   * SETTINGS max_insert_block_size bounds the rows per part-forming block; peak insert memory is a small multiple of
--     the smaller of that and min_insert_block_size_bytes (256 MB default), which dominates for wide trace rows.
--   * SETTINGS max_partitions_per_insert_block is REQUIRED, not a tuning knob. Because the blocks above may span
--     partitions (see the ORDER BY note), and the destination is weekly-partitioned on id_at, a block spans as many
--     partitions as the ids in it imply. ClickHouse's default is 100 and throw_on_max_partitions_per_insert_block = 1,
--     so exceeding it ABORTS the INSERT. Far-future UUIDv7 ids (litellm BerriAI/litellm#31294) put real tables well
--     past it: measured on a production-shape table, one block spanned 333 destination partitions in total (269 of them
--     far-future, the rest ordinary weeks the same block touched) while every other block in that window spanned <= 7.
--     The driver's default (2000) is sized to clear the table's TOTAL distinct partition count with margin -- not just
--     the far-future count, which the 333-vs-269 gap shows is an undercount of a block's real spread.

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
         max_partitions_per_insert_block = ${MAX_PARTITIONS_PER_INSERT_BLOCK},
         max_insert_threads = ${MAX_INSERT_THREADS},
         log_comment = 'traces_local_v2_backfill:${WINDOW_LO}:${WINDOW_HI}';

-- Per-window reconciliation is automated by backfill.sh (uniqExact of the dedup key, aborting on > 0.01% divergence);
-- fidelity QA across the whole copy is 000005 via verify.sh. Rollback before the EXCHANGE: rollback.sh --stage A.
