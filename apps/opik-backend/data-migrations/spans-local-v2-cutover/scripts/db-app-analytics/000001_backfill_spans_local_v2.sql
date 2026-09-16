-- runbook spans-local-v2-cutover — step 1 of 3: backfill (reference statements)
-- The gate test SpansLocalV2CutoverTest reimplements these statements inline; keep the two in step (see its Javadoc).
--
-- This file is the SINGLE source of the backfill INSERT; ../backfill.sh reads ONE MARKED BLOCK, substitutes the ${...}
-- placeholders and runs it per time sub-window — so the script and this reference never drift. Run the migration
-- through backfill.sh, never this file by hand. WINDOW_LO/WINDOW_HI are a created_at half-open range the driver picks
-- so each INSERT stays under its --max-rows-per-insert bound (see README "Batching and throttling").
--
-- ONE BLOCK, AND WHY THAT IS THE RIGHT ANSWER HERE. This is the traces statement with spans' columns and spans'
-- thresholds. The destination partitions weekly on the honest Monday of id_at, so a created_at window contains rows
-- landing in many destination partitions — far-future ids (litellm BerriAI/litellm#31294 mints ~2201) spread across a
-- long stretch of weeks, and non-v7 ids collapse into the epoch week. ClickHouse caps partitions per insert block at
-- 100 by default and, with throw_on_max_partitions_per_insert_block = 1, ABORTS the INSERT rather than degrading; the
-- traces cutover hit exactly that. Raising max_partitions_per_insert_block above the table's total partition count is
-- the whole fix, and it is the same fix traces applied. See README "Partition spread, and the one setting that
-- matters" for why nothing more elaborate is warranted -- including the measurements that retired an earlier two-pass
-- split from this file.
--
-- THE TWO SETTINGS THAT MATTER, AND WHICH ONE IS WHICH:
--   * max_partitions_per_insert_block is a LIMIT CHECK, not an allocation. Set it above the destination's total
--     distinct partition count and TOO_MANY_PARTS becomes unreachable; it costs nothing when a block touches few
--     partitions, which is the normal case.
--   * min_insert_block_size_bytes is the MEMORY AND PART-COUNT DIAL, and the only one. It caps a block in bytes, so at
--     spans' row width a block holds a few thousand rows -- and a block can never span more partitions than it holds
--     rows. Lowering it lowers peak insert memory and raises the part count; raising it does the reverse. README's
--     threshold table carries the measured pairs.
--
-- ALL SIX placeholders the driver substitutes, so a new one is never missed here (an unsubstituted ${...} reaches the
-- server as a literal and the INSERT fails):
--   ${ANALYTICS_DB_DATABASE_NAME}          the analytics database
--   ${WINDOW_LO} / ${WINDOW_HI}            the created_at half-open window bounds
--   ${MAX_INSERT_BLOCK_SIZE}               rows per part-forming block (rarely the binding one on spans)
--   ${MIN_INSERT_BLOCK_SIZE_BYTES}         bytes per part-forming block — the bound that actually binds on spans
--   ${MAX_PARTITIONS_PER_INSERT_BLOCK}     partitions one block may span (required; see the note below)
--   ${MAX_INSERT_THREADS}                  threads for the INSERT SELECT pipeline. The driver OMITS this whole
--                                          SETTINGS line when --max-insert-threads is unset, so the server's
--                                          value is inherited; an explicit 0 forces no parallel execution.
--
-- EVERY WINDOW BOUND IN THIS RUNBOOK PINS 'UTC'. Unpinned, a literal is parsed in the SERVER timezone while these
-- columns are DateTime64(n, 'UTC'), so on a non-UTC server a bound resolves to a different instant than intended --
-- and a bound that lands LATER silently drops the rows in the gap, which the delta and the deletion replay both miss
-- because they share it. Where a bound is a value the driver captured, the capture pins 'UTC' too: see
-- ${BACKFILL_START} in 000002.
--
-- The epoch sentinel below is the deliberate exception: it stays unpinned, matching the destination table's own
-- DEFAULT and duration expression and the application's embedded SQL comparisons. Pinning it here alone would give
-- migrated rows a sentinel none of those match -- duration would read a large number instead of NaN. The asymmetry
-- 000001 of the traces runbook records applies verbatim here.
--
-- SPANS-SPECIFIC PROJECTION NOTES (the four places this is not the traces statement with a different table name):
--   * parent_span_id: String on the source, FixedString(36) on the destination. It is NOT always a UUID or the empty
--     sentinel. SpanDAO's PARTIAL_INSERT writes leftPad('', 40, '*') -- a FORTY-character poison value -- into this
--     column when a span's parent changes; on `spans` that column is a plain String, so the write SUCCEEDS and the
--     value is stored. An implicit String -> FixedString(36) conversion of a 40-byte value THROWS
--     (TOO_LARGE_STRING_SIZE), which would abort the whole window. So the projection maps any value that is not
--     exactly 36 bytes to the empty (root-span) sentinel, which the destination stores as 36 NUL bytes and every
--     reader treats as absent. That is a deliberate, recorded normalization of a value that was never a real span id:
--     ../estimate.sh counts the affected rows before the window opens and the runbook's Go/No-Go gates on it, and the
--     original bytes stay in spans_pre_cutover_backup until finalize.sh.
--   * usage: Map(String, Int32) -> Map(String, Int64) is a lossless widening ClickHouse performs implicitly; it is
--     projected as-is, and 000005's fingerprint canonicalizes both sides to the same text so the widening cannot read
--     as a difference.
--   * end_time and ttft are the two denullified columns: coalesce them to their sentinels (epoch / NaN), exactly as
--     the traces cutover does.
--   * There is NO output_keys column on either side (it is traces-only, added by migration 000044), and no thread_id
--     or visibility_mode. Do not go looking for them. The spans-only columns the traces statement has no counterpart
--     for are trace_id, parent_span_id, type, model, provider, total_estimated_cost,
--     total_estimated_cost_version and usage.
--   * is_deleted is omitted so the new column defaults to 0.
--   * apply_deleted_mask stays at its default 1, so rows already lightweight-deleted on the source are skipped.
--   * No explicit ORDER BY: omitted deliberately to avoid a full per-window sort (memory). A parallel SELECT gives no
--     output-order guarantee, so inserted blocks may span/interleave partitions; the destination ReplacingMergeTree
--     dedups regardless of insert order and background merges compact the parts.
--   * SETTINGS max_partitions_per_insert_block is REQUIRED, not a tuning knob, for the reason the traces runbook
--     gives: the default of 100 ABORTS the INSERT, and a created_at window's rows reach far more destination
--     partitions than that. It is a limit check rather than an allocation -- ClickHouse buffers ~34 KiB per partition
--     a block ACTUALLY touches, not per partition permitted -- so setting it above the table's total distinct
--     partition count is free. estimate.sh audit 1 measures that count.
--   * SETTINGS min_insert_block_size_bytes is carried EXPLICITLY here, unlike in the traces statement which left it at
--     the 256 MiB default, because on spans it is the bound that binds AND the only dial over peak insert memory:
--     rows are wide uncompressed, so the row-count cap never fires first. It also bounds the partition term by
--     construction, a block being unable to span more partitions than it holds rows. README's threshold table carries
--     the measured memory/part-count pairs for the values worth choosing between.

-- >>> BEGIN backfill
-- ONE INSERT per created_at window -- the same shape the traces cutover ran, with the partition cap re-derived.
INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 (
    id,
    workspace_id,
    project_id,
    trace_id,
    parent_span_id,
    name,
    type,
    start_time,
    end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
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
    trace_id,
    if(length(parent_span_id) = 36, toFixedString(parent_span_id, 36), toFixedString('', 36)) AS parent_span_id,
    name,
    type,
    start_time,
    coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
    truncation_threshold,
    input_slim,
    output_slim,
    coalesce(ttft, toFloat64('nan')) AS ttft,
    source,
    environment
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans
WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
  AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
SETTINGS max_insert_block_size = ${MAX_INSERT_BLOCK_SIZE},
         min_insert_block_size_bytes = ${MIN_INSERT_BLOCK_SIZE_BYTES},
         max_partitions_per_insert_block = ${MAX_PARTITIONS_PER_INSERT_BLOCK},
         max_insert_threads = ${MAX_INSERT_THREADS},
         log_comment = 'spans_local_v2_backfill:${WINDOW_LO}:${WINDOW_HI}';
-- >>> END backfill

-- Per-window reconciliation is automated by backfill.sh. It counts uniqExact of the DESTINATION dedup key
-- (workspace_id, project_id, trace_id, id) on BOTH sides -- not the source's own key, which additionally carries
-- parent_span_id. That difference is real, not cosmetic: `spans` can legitimately hold two live rows for one span
-- under different parent_span_id values (SpanDAO's PARTIAL_INSERT writes a changed parent, and a mutable sort-key
-- column never merges), and the destination collapses them to one. Counting the source's own key would therefore
-- report a faithful copy as short. Fidelity QA across the whole copy is 000005 via verify.sh. Rollback before the
-- EXCHANGE: rollback.sh --stage A.
