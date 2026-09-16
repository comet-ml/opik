-- runbook spans-local-v2-cutover — step 2 of 3: delta-insert + deletion replay
-- The gate test SpansLocalV2CutoverTest reimplements these statements inline; keep the two in step (see its Javadoc).
-- Run this only after the whole backfill (step 1) is complete and reconciled.

-- Step 0: The SQL below (delta-insert + deletion replay) is the single source driven by ../delta_replay.sh, which reads
-- this file, substitutes the placeholders and runs it — never run this file by hand. It handles all six
-- placeholders below, listed so a new one is never missed here (an unsubstituted ${...} reaches the server as a
-- literal and the statement fails): ${ANALYTICS_DB_DATABASE_NAME}, ${BACKFILL_START}, ${MAX_INSERT_BLOCK_SIZE},
-- ${MIN_INSERT_BLOCK_SIZE_BYTES}, ${MAX_PARTITIONS_PER_INSERT_BLOCK} and ${MAX_INSERT_THREADS} -- the last of which the
-- driver instead REMOVES from the SETTINGS clause when --max-insert-threads is omitted, so the server's value is
-- inherited. Invocation:
--   ../delta_replay.sh --database opik --backfill-start '2026-06-01 12:00:00.000000 UTC'
-- The ' UTC' marker is mandatory: the driver refuses an anchor without it, because the bounds below parse the
-- value as UTC and one captured elsewhere would shift them silently.
-- The go/no-go checkpoint between the steps stays with the operator, where situational awareness matters most — that is
-- judgement, not SQL. The driver invokes clickhouse-client with --time, so it prints each statement's elapsed seconds
-- to stderr (delta-insert first, deletion replay second); the second value is the replay measurement in step 4. A bare
-- --query prints no timing, hence the flag.

-- Step 1: BACKFILL_START is the timestamp captured BEFORE the backfill began. backfill.sh prints it at startup
-- ("RECORD backfill_start=..."); if you ran the backfill manually, use the now64(6, 'UTC') you captured before the first
-- INSERT. The delta and the replay both key off this single anchor, so writes during the whole backfill window are
-- covered.

-- Step 2: Delta-insert — re-copy every row written during the backfill window. Anchored on
-- created_at OR last_updated_at >= backfill_start (NOT last_updated_at alone): last_updated_at is client-supplied on the
-- batch-ingest path, so it is not a reliable "changed since" signal by itself. Every span write sets EITHER a fresh
-- server created_at (batch-ingest path) OR a fresh server last_updated_at (create/update merge paths), so the union is
-- complete. ReplacingMergeTree dedups the re-copied rows against the backfilled ones (newest last_updated_at wins).
--
-- THE DELTA IS NOT SPLIT INTO TWO PASSES, unlike the backfill, and that is a decision rather than an omission. The
-- split in 000001 works because a backfill window is a bounded created_at range, so "the honest id_at band this window
-- implies" is a finite, computable interval. The delta has no such window: it is everything written since
-- backfill_start, whose ids can be ANY age — its `last_updated_at` arm exists precisely to re-copy UPDATES TO OLD ROWS,
-- which is the arm that carries the far-future ids. So one delta statement can touch far-future, epoch and ordinary
-- partitions together, and it runs at the large
-- max_partitions_per_insert_block, with min_insert_block_size_bytes and max_insert_block_size tightened so the block's
-- row-data term stays small beside the ~34 KiB-per-partition buffers (README, "Blocker 2"). The delta is small relative
-- to the backfill, so paying the full partition-buffer cost on it is affordable; paying it on every backfill block
-- would not be.
--
-- Exceeding ClickHouse's default of 100 partitions per block aborts the statement
-- (throw_on_max_partitions_per_insert_block = 1) at the worst possible moment: the final delta runs immediately before
-- the EXCHANGE. Pass the same value the backfill used.
-- BATCHING: the delta covers only writes during the backfill window, not the whole table, so it is normally one
-- statement. If the backfill ran for days on a busy system and the delta is large, run it as two batched passes to keep
-- each INSERT bounded (both columns have a minmax skip index, so each pass prunes):
--   (a) created_at >= backfill_start                                  -- batch by created_at sub-windows
--   (b) last_updated_at >= backfill_start AND created_at < backfill_start  -- the updates-to-old-rows arm; batch by
--       last_updated_at sub-windows. (a) ∪ (b) equals the OR below, with no overlap.
-- Both passes are hand-written: the driver does not implement the split, so its substitution and guards do not
-- apply to them. Prepare each pass as follows.
--   1. Copy the SETTINGS block below onto both passes, max_partitions_per_insert_block and
--      min_insert_block_size_bytes included. Arm (b) is the updates-to-old-rows arm, so it carries the far-future ids
--      and is the pass that most needs both; omitting either aborts the statement, or spikes its memory, immediately
--      before the EXCHANGE.
--   2. Replace every ${...} with a concrete value. A copied placeholder reaches the server as a literal and the
--      statement fails.
--   3. max_insert_threads has no value meaning "inherit": 0 forces no parallel execution rather than deferring to
--      the server's setting. Either substitute the same thread count on both passes, or delete the whole line
--      including its trailing comma from both, keeping the other settings and log_comment. Deleting
--      is comma-safe only while the line sits between two others.
--   4. Confirm no placeholder survives: `grep -n '\${' <your-statements>.sql` must print nothing.
--
-- SPANS-SPECIFIC PROJECTION NOTES: identical to 000001's, and they must stay identical — the parent_span_id
-- length guard, the Map(String, Int32) -> Map(String, Int64) widening, the end_time / ttft sentinels, the absent
-- output_keys / thread_id / visibility_mode. See that file's header for why each is what it is.
-- >>> BEGIN delta-insert
-- max_insert_threads below sizes the INSERT SELECT pipeline, with the same semantics and costs as in 000001:
-- omitted when --max-insert-threads is unset, an explicit 0 forcing no parallel execution. The delta writes to
-- the same table through the same insert path, so pass the value used for the backfill.
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
-- ${BACKFILL_START} is half of a pair: backfill.sh captures it with now64(6, 'UTC') and every bound below parses it
-- as 'UTC'. Pinning only one half shifts the anchor by the server's offset, and a LATER anchor silently drops the
-- rows written in the gap — the delta and the deletion replay share this bound, so neither would see them.
WHERE created_at >= toDateTime64('${BACKFILL_START}', 6, 'UTC')
   OR last_updated_at >= toDateTime64('${BACKFILL_START}', 6, 'UTC')
SETTINGS max_insert_block_size = ${MAX_INSERT_BLOCK_SIZE},
         min_insert_block_size_bytes = ${MIN_INSERT_BLOCK_SIZE_BYTES},
         max_partitions_per_insert_block = ${MAX_PARTITIONS_PER_INSERT_BLOCK},
         max_insert_threads = ${MAX_INSERT_THREADS},
         log_comment = 'spans_local_v2_cutover:delta_insert';
-- >>> END delta-insert

-- Step 3: Deletion replay — remove from the destination every row that was deleted on the source since backfill_start
-- AND is still deleted there.
--
-- WHERE SPAN DELETES COME FROM, WHICH IS NOT WHERE TRACE DELETES COME FROM. Spans have no standalone delete endpoint:
-- every user-facing span delete is the CASCADE of a trace delete, through SpanService.deleteByTraceIds, which resolves
-- the span ids of the deleted traces and removes them under (workspace_id, project_id, id). That cascade is the ONLY
-- capture path, and it always carries a project_id — TraceDeletedListener receives it from the TracesDeleted event,
-- which since OPIK-7483 is only ever emitted per resolved project. So no deletion event is ever bridged with an empty
-- project_id for source_table='spans' either (a pre-cutover prereq asserts the bridge holds none — see README
-- "Prerequisites"), and the replay carries a single FULL-KEY branch, exactly as the traces one does.
--
-- THE KEY IS (workspace_id, project_id, id), AND THAT IS NOT A PRIMARY-KEY PREFIX HERE. On traces it was the whole
-- primary key. spans_local_v2 orders by (workspace_id, project_id, trace_id, id), and the bridge does not record
-- trace_id — DeletionEvent carries (source_table, workspace_id, project_id, deleted_id) and nothing else. So this
-- predicate prunes on the (workspace_id, project_id) prefix and then relies on the id skip indexes 000115 added for
-- exactly this shape (idx_spans_id_minmax and idx_spans_id_bf, the bloom filter being the one that prunes an
-- equality/IN set within a week's shared UUIDv7 prefix). It is the same predicate SpanDAO.DELETE_BY_IDS issues against
-- the live table, so the cutover is not asking for a read shape the product does not already make.
--
-- RESURRECTION GUARD (the `NOT IN spans` arm): a span can be deleted and then re-created/updated under the same id
-- during the window (span ids are client-supplied; the delete is a mask, a newer insert wins under FINAL). Such an id is
-- bridged as deleted but is LIVE again on the source, and the backfill/delta already copied its live version. Deleting
-- it by key would drop a row that is live on the source — silent data loss. So the replay deletes only ids that are NOT
-- currently live on the source (mask-honored). The guard covers a second case since OPIK-8141: capture runs before the
-- delete, so the bridge can name an id whose delete then errored and is still live on the source. Same arm, same reason.
--
-- THE GUARD'S SUBQUERY READS THE WHOLE TABLE WITHOUT AN id INDEX, WHICH IS WORSE HERE THAN ON TRACES. The
-- `id IN (deleted_ids since anchor)` set is tiny (retention is off, so these are user-scale cascade deletes), but
-- `spans` has NO id skip index: migration 000088 indexes only created_at/last_updated_at, and the id minmax/bloom
-- indexes exist only on spans_local_v2 (000115) and, for traces, on `traces` (000113). So this is a bounded id-filtered
-- read over the full id column of the source table, not a value-indexed prune. Adding the traces-000113
-- equivalent to `spans` is deliberately NOT part of this cutover: materializing a bloom filter over a table this size is a
-- heavy mutation and would have to run inside the very window this procedure asks to keep short. Measure the replay's
-- wall time on prod-test (delta_replay.sh prints it) and treat it as a first-class component of the tail, not a
-- rounding error as it was on traces.
--
-- allow_nondeterministic_mutations: a lightweight DELETE with cross-table subqueries is flagged nondeterministic, but
-- deletion_events_local and spans are replicated and identical on every node and the window predicate is fixed, so the
-- subqueries resolve to the same set on every replica. Idempotent (never masks a live-on-source id, so re-runs converge).
-- lightweight_deletes_sync = 2: block until the delete mutation has completed on EVERY replica, not just the one that
-- accepted it. The mutation is otherwise asynchronous, so without this the verify step (and the EXCHANGE) could run
-- against a replica where the mask is not yet applied — a false mismatch, or worse an incomplete cutover.
-- Uses ${BACKFILL_START}. Retention is disabled everywhere (see step 5), so this is user-scale volume — a single
-- mutation. If it is ever large (e.g. retention enabled), bound each mutation by a created_at week (the non-wrapping,
-- minmax-indexed slice backfill.sh uses) and loop the weeks — NOT toMonday(id_at), which wraps far-future/epoch ids and
-- no longer matches the successor's honest-Date32 partition expression.
-- length(...) = 36 guards: toFixedString(x, 36) THROWS on a value longer than 36 bytes, which would abort the whole
-- replay on a single malformed bridge row. For source_table='spans' the ids are 36-char UUIDs, so this is latent — but
-- a malformed (non-36-char) deleted_id/project_id can't match a real span id anyway, so skipping it via the length
-- guard loses nothing and turns a hard abort mid-cutover into a benign no-op. Same guards in the reverse-replay.
-- >>> BEGIN deletion-replay
DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2
WHERE (
    (workspace_id, project_id, id) IN (
        SELECT
            workspace_id,
            toFixedString(project_id, 36),
            toFixedString(deleted_id, 36)
        FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
        WHERE source_table = 'spans'
          AND event_time >= toDateTime64('${BACKFILL_START}', 6, 'UTC')
          AND project_id != ''
          AND length(project_id) = 36
          AND length(deleted_id) = 36
    )
    AND (workspace_id, project_id, id) NOT IN (
        SELECT
            workspace_id,
            project_id,
            id
        FROM ${ANALYTICS_DB_DATABASE_NAME}.spans
        WHERE id IN (
            SELECT toFixedString(deleted_id, 36)
            FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
            WHERE source_table = 'spans'
              AND event_time >= toDateTime64('${BACKFILL_START}', 6, 'UTC')
              AND length(deleted_id) = 36
        )
    )
)
SETTINGS allow_nondeterministic_mutations = 1,
         lightweight_deletes_sync = 2,
         log_comment = 'spans_local_v2_cutover:deletion_replay';
-- >>> END deletion-replay

-- Step 4: Measure the replay. Its wall time is the first half of the final-delta -> EXCHANGE gap (exchange_and_wrap.sh
-- reports the second half, its own run through the swap), and that gap is where tail writes are left behind
-- (OPIK-8238), so keeping it short keeps that set small. On spans this measurement matters more than it did on traces:
-- the resurrection guard's unindexed id read is over the whole table (see step 3). Re-run steps 2-3 if new rows/deletes
-- accumulated during the replay itself. Note the anchor is fixed, so a re-run re-copies the WHOLE window rather than
-- only what is new — the statement does not get cheaper, and ReplacingMergeTree dedups the re-copies. What shrinks is
-- the residual: after each pass, only the writes that arrived during that pass are uncaught.

-- Step 5 (retention — see README): Data Retention is disabled in every deployment (RETENTION_ENABLED=false), so the
-- retention delete path does not fire during the cutover. The only deletes in this window are user-initiated cascades,
-- and those ARE captured by the bridge. If retention is ever enabled, pause it for the window (or land retention-path
-- capture). Note the spans retention sweep is WORSE than the traces one to leave running: SpanDAO.DELETE_FOR_RETENTION
-- filters on trace_id only and applies no partition-pruning predicate at all (OPIK-8364, half B), so it is planned
-- against every part of the table.

-- rollback: none for the delta-insert (it only adds newest versions that ReplacingMergeTree dedups); the replay is
--           idempotent. If aborting the cutover here, TRUNCATE spans_local_v2 (rollback.sh --stage A) and see the
--           README on the spanColumnsNonNullable revert and sentinel repair that an abandoned run still owes. The
--           live `spans` table is untouched until the EXCHANGE in 000003.
