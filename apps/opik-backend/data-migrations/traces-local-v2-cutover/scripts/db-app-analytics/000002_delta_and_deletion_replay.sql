-- runbook traces-local-v2-cutover — step 2 of 3: delta-insert + deletion replay
-- Run this only after the whole backfill (step 1) is complete and reconciled.

-- Step 0: The SQL below (delta-insert + deletion replay) is the single source driven by ../delta_replay.sh, which reads
-- this file, substitutes the placeholders and runs it — never run this file by hand:
--   ../delta_replay.sh --database opik --backfill-start '2025-06-01 12:00:00.000000'
-- The surrounding config operations (buffer raise/restore) and the go/no-go checkpoint stay with the operator, where
-- situational awareness matters most — those are config/judgement, not SQL. clickhouse-client prints each statement's
-- elapsed time, which is the replay measurement in step 5.

-- Step 1: BACKFILL_START is the timestamp captured BEFORE the backfill began. backfill.sh prints it at startup
-- ("RECORD backfill_start=..."); if you ran the backfill manually, use the now64(6) you captured before the first
-- INSERT. The delta and the replay both key off this single anchor, so writes during the whole backfill window are
-- covered.

-- Step 2: Raise the async-insert buffer ceiling so the buffer can absorb the cutover window. Set
-- databaseAnalytics.asyncInsertBusyTimeoutMaxMs ~= 10000 (env ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS) and roll
-- it out (config push + rolling restart, OR a session-level SET on a dedicated cutover connection). Because
-- async_insert_use_adaptive_busy_timeout=1, this only widens the buffer while rows are queued. VERIFY the widening
-- took effect before proceeding — see README.

-- Step 3: Delta-insert — re-copy every row written during the backfill window. Anchored on
-- created_at OR last_updated_at >= backfill_start (NOT last_updated_at alone): last_updated_at is client-supplied on the
-- batch-ingest path, so it is not a reliable "changed since" signal by itself. Every trace write sets EITHER a fresh
-- server created_at (batch-ingest path) OR a fresh server last_updated_at (create/update merge paths), so the union is
-- complete. ReplacingMergeTree dedups the re-copied rows against the backfilled ones (newest last_updated_at wins).
-- Uses ${BACKFILL_START}. SETTINGS max_insert_block_size bounds per-block memory as in step 1.
-- BATCHING: the delta covers only writes during the backfill window, not the whole table, so it is normally one
-- statement. If the backfill ran for days on a busy system and the delta is large, run it as two batched passes to keep
-- each INSERT bounded (both columns have a minmax skip index, so each pass prunes):
--   (a) created_at >= backfill_start                                  -- batch by created_at sub-windows
--   (b) last_updated_at >= backfill_start AND created_at < backfill_start  -- the updates-to-old-rows arm; batch by
--       last_updated_at sub-windows. (a) ∪ (b) equals the OR below, with no overlap.
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
WHERE created_at >= toDateTime64('${BACKFILL_START}', 6)
   OR last_updated_at >= toDateTime64('${BACKFILL_START}', 6)
SETTINGS max_insert_block_size = ${MAX_INSERT_BLOCK_SIZE};

-- Step 4: Deletion replay — remove from the destination every row that was deleted on the source since backfill_start
-- AND is still deleted there. Two branches, mirroring the product's two delete paths (TraceService.delete): a delete
-- resolves each trace's owning project and deletes per project; ids it cannot resolve fall back to a workspace-scoped
-- delete (TraceDAO DELETE_BY_ID with no project filter). The bridge records the first with the project and the second
-- with an EMPTY project_id (DeletionEventDAO: "project_id is empty for workspace-scoped source tables"). So:
--   * events WITH a project -> match the FULL key (workspace_id, project_id, id). Exact, prunes on the destination
--     primary key, and correct even when an id is reused across projects (ids are not globally unique).
--   * events WITHOUT a project -> match (workspace_id, id). A faithful mirror of the source's workspace-scoped delete.
-- RESURRECTION GUARD (the `NOT IN traces` arm): a trace can be deleted and then re-created/updated under the same id
-- during the window (client-supplied ids; the delete is a mask, a newer insert wins under FINAL). Such an id is bridged
-- as deleted but is LIVE again on the source, and the backfill/delta already copied its live version. Deleting it by key
-- would drop a row that is live on the source — silent data loss. So each branch deletes only ids that are NOT currently
-- live on the source (mask-honored). The `id IN (deleted_ids since anchor)` bound keeps the source lookup pruned to the
-- window's deletes (id skip index), not a full scan.
-- allow_nondeterministic_mutations: a lightweight DELETE with cross-table subqueries is flagged nondeterministic, but
-- deletion_events_local and traces are replicated and identical on every node and the window predicate is fixed, so the
-- subqueries resolve to the same set on every replica. Idempotent (never masks a live-on-source id, so re-runs converge).
-- lightweight_deletes_sync = 2: block until the delete mutation has completed on EVERY replica, not just the one that
-- accepted it. The mutation is otherwise asynchronous, so without this the verify step (and the EXCHANGE) could run
-- against a replica where the mask is not yet applied — a false mismatch, or worse an incomplete cutover.
-- Uses ${BACKFILL_START}. Retention is disabled everywhere (see step 6), so this is user-scale volume — a single
-- mutation. If it is ever large (e.g. retention enabled), bound each mutation by a partition predicate and loop the
-- weeks, e.g. AND toMonday(id_at) = toDate('<week>').
DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2
WHERE (
    (workspace_id, project_id, id) IN (
        SELECT
            workspace_id,
            toFixedString(project_id, 36),
            toFixedString(deleted_id, 36)
        FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
        WHERE source_table = 'traces'
          AND event_time >= toDateTime64('${BACKFILL_START}', 6)
          AND project_id != ''
    )
    AND (workspace_id, project_id, id) NOT IN (
        SELECT
            workspace_id,
            project_id,
            id
        FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
        WHERE id IN (
            SELECT toFixedString(deleted_id, 36)
            FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
            WHERE source_table = 'traces'
              AND event_time >= toDateTime64('${BACKFILL_START}', 6)
        )
    )
)
OR (
    (workspace_id, id) IN (
        SELECT
            workspace_id,
            toFixedString(deleted_id, 36)
        FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
        WHERE source_table = 'traces'
          AND event_time >= toDateTime64('${BACKFILL_START}', 6)
          AND project_id = ''
    )
    AND (workspace_id, id) NOT IN (
        SELECT
            workspace_id,
            id
        FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
        WHERE id IN (
            SELECT toFixedString(deleted_id, 36)
            FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
            WHERE source_table = 'traces'
              AND event_time >= toDateTime64('${BACKFILL_START}', 6)
        )
    )
)
SETTINGS allow_nondeterministic_mutations = 1,
         lightweight_deletes_sync = 2;

-- Step 5: Measure the replay. Compare its wall time against the buffer window (must fit with margin — acceptance
-- criterion). Re-run steps 3-4 if new rows/deletes accumulated during the replay itself; convergence is fast because
-- the buffer is holding new writes.

-- Step 6 (retention — see README): Data Retention is disabled in every deployment (RETENTION_ENABLED=false), so the
-- retention delete path does not fire during the cutover. The only deletes in this window are user-initiated, and those
-- ARE captured by the bridge. If retention is ever enabled, pause it for the window (or land retention-path capture).

-- rollback: none for the delta-insert (it only adds newest versions that ReplacingMergeTree dedups); the replay is
--           idempotent. If aborting the cutover here, TRUNCATE traces_local_v2 (step 1 rollback) and restore the buffer
--           ceiling (step 2, reverse). The live `traces` table is still untouched until the EXCHANGE in step 3.
