-- runbook spans-local-v2-cutover — ROLLBACK reverse-replay (driven by ../rollback.sh after stage B or C)
-- The gate test SpansLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Re-applies the deletes that fired on the successor since cutover_start onto the restored original `spans`, so they do
-- not resurrect. Single FULL-KEY branch, like the forward replay in 000002: every span delete is the cascade of a trace
-- delete and carries its project_id (TraceDeletedListener passes the TracesDeleted event's, which since OPIK-7483 is
-- always a resolved project), so the replay matches (workspace_id, project_id, id); the post-cutover deletes it replays
-- come from the live successor, which routes through that same cascade. Shared by stages B and C (run right after the
-- swap/promote), never on its own. If the set is ever large, bound it by a created_at week (the non-wrapping,
-- minmax-indexed slice backfill.sh uses) and loop the weeks — NOT toMonday(id_at), which wraps far-future/epoch ids; the
-- restored original `spans` is not weekly-partitioned by it anyway.
--
-- Deliberately NO resurrection guard (the `AND ... NOT IN spans` arm the forward replay in 000002 carries). Do NOT add
-- one here: rollback abandons all post-cutover writes on the successor (they are being discarded) while still honoring
-- post-cutover deletes. `spans` here is the RESTORED ORIGINAL, so a bridged id is present as its pre-cutover version; a
-- liveness guard would spare it and thereby UNDO the user's post-cutover delete (resurrecting stale content). Masking it
-- unconditionally is the correct rollback semantics. Nor a guard reading the parked successor as a proxy for "the delete
-- applied": an id deleted and then RE-CREATED post-cutover is live there too, so that one would spare it just the same.
--
-- One consequence, given the capture ordering (OPIK-8141): capture runs before the lightweight delete, so the bridge can
-- name an id whose delete then errored and never applied. Having no liveness guard, this replay masks it on the restored
-- original anyway. Accepted — the user did ask for that delete — and the alternative ordering instead loses genuine
-- deletes, which resurrect here with the postcondition check reading the same bridge and so reporting 0. Such an id is
-- recoverable while the rollback window is open: this statement touches only `spans`, so the row is still live on the
-- parked successor (`spans_post_rollback_backup`) until finalize.sh drops it — the point of no return.
--
-- ON THE ORIGINAL `spans` THIS PREDICATE PRUNES LESS WELL THAN ITS TRACES COUNTERPART DID, in the other direction from
-- the forward replay's problem. The source table's ORDER BY is (workspace_id, project_id, trace_id, parent_span_id, id),
-- so (workspace_id, project_id, id) prunes on the two-column prefix and then scans — and `spans` carries no id skip
-- index (000088 indexes only created_at/last_updated_at; the id minmax/bloom pair exists on spans_local_v2 per 000115,
-- and on `traces` per 000113, but not here). Retention is off, so the bridged set is user-scale and the mutation is one
-- statement — but its wall time is a real component of the rollback window on a table this size. rollback.sh passes
-- --time so the figure is recorded rather than estimated.
--
-- ${PARTITION_SCOPE}, AND WHY IT RENDERS EMPTY HERE (OPIK-8607). The other two replays carry this placeholder because
-- an unbounded mutation allocates a block number in every partition in one atomic ZooKeeper request, which past
-- `jute.maxbuffer` kills the session (see 000002 step 3). THIS replay does not have that problem: its target is the
-- RESTORED ORIGINAL `spans`, created by 000001_init_script with no `PARTITION BY` at all, so the table has exactly one
-- partition and an unbounded mutation locks exactly one block number. Stages B and C both rename the original back
-- under this name BEFORE the driver runs this file, and reconcile.sh's reverse direction re-runs it against the same
-- restored original, so this statement never meets the partitioned successor.
--
-- Scoping it unconditionally would not merely be pointless, it would BREAK THE ROLLBACK: `DELETE ... IN PARTITION <p>`
-- against a table whose partition key has no columns is rejected outright (`INVALID_PARTITION_VALUE`). So the
-- placeholder is here and rollback.sh renders it EMPTY, because it asks `system.tables.partition_key` what the live
-- target is rather than assuming — the same check all four drivers make, from one shared code path. What that buys is
-- that the day `spans` itself becomes weekly-partitioned (OPIK-6900) this file needs no edit: the driver starts
-- scoping it because the table started reporting the weekly key. Until then the rendered statement is byte-identical
-- to what shipped before OPIK-8607.
--
-- ${BRIDGE_WINDOW_END} rides along for the same reason and renders empty here too. It closes the bridge match at the
-- instant the scope was derived, which is what stops a scoped statement matching an id whose partition the scope does
-- not name; the unbounded form names no partitions and so needs no such bound, and must not carry one — closing its
-- window would narrow the pass for nothing.
-- >>> BEGIN reverse-replay
DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.spans
${PARTITION_SCOPE}
WHERE (workspace_id, project_id, id) IN (
    SELECT
        workspace_id,
        toFixedString(project_id, 36),
        toFixedString(deleted_id, 36)
    FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
    WHERE source_table = 'spans'
      AND event_time >= toDateTime64('${CUTOVER_START}', 6, 'UTC')
      ${BRIDGE_WINDOW_END}
      AND project_id != ''
      AND length(project_id) = 36
      AND length(deleted_id) = 36
)
-- lightweight_deletes_sync = 2: wait for the mutation on every replica so the restored `spans` is consistent
-- cluster-wide before the rollback is declared done (see 000002 for the rationale).
SETTINGS allow_nondeterministic_mutations = 1,
         lightweight_deletes_sync = 2,
         log_comment = 'spans_local_v2_rollback:reverse_replay';
-- >>> END reverse-replay
