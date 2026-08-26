-- runbook traces-local-v2-cutover — ROLLBACK reverse-replay POSTCONDITION (driven by ../rollback.sh, after the replay)
--
-- Counts ids the bridge recorded as deleted since cutover_start that are LIVE again on the restored `traces`. The answer
-- must be 0; anything else means the reverse replay did not take, and rows a user deleted after the cutover are being
-- served again.
--
-- WHY THIS IS A SEPARATE ASSERTION AND NOT AN INFERENCE. The replay reports that its statement ran, not that the result
-- holds: a lightweight DELETE whose predicate matched nothing succeeds. And the fidelity compare cannot stand in for it —
-- verify.sh windows on `created_at`, and the post-rollback compare is bounded below the cutover window, so a row created
-- *inside* that window and deleted after it falls outside every window the compare looks at. This is the only check that
-- covers those rows.
--
-- KEEP IN STEP WITH 000004_rollback_reverse_replay.sql. The key and the filters below are deliberately identical to the
-- replay's: same (workspace_id, project_id, id) tuple, same toFixedString(36) casts onto the bridge's String columns, and
-- the same project_id/deleted_id length guards. A check that filtered differently from the replay would either miss what
-- the replay missed or flag rows the replay was never asked to touch. Change one, change both.
--
-- WHAT 0 PROVES, AND WHAT IT DOES NOT. It proves every delete the bridge *recorded* in the window is masked. It cannot
-- see a delete the bridge never recorded: capture runs after the delete succeeds and is best-effort by design (an
-- auxiliary insert must never fail a user's delete), so a delete still in flight when this runs, or one whose capture
-- errored, is invisible to the replay and to this check alike. That gap is closed by quiescing trace deletes before the
-- promote — see the runbook — not by this query.
--
-- clusterAllReplicas: the mask is per-replica state. The replay runs with lightweight_deletes_sync = 2, so it has
-- converged on every replica before the driver returns — reading every replica is what turns that into an observation
-- rather than an assumption, and it is where a replica that fell behind would show up.
--
-- uniqExact, not count(): reading every replica returns the same row once per replica, so count() would report a single
-- resurrected id as N on an N-replica shard. Counting distinct keys keeps the number meaning "ids live again" on any
-- topology, while still detecting a row that is masked on one replica and live on another (it counts once, which is
-- correct — one id needs attention). Same aggregate the backfill's reconciliation uses.

SELECT uniqExact(workspace_id, project_id, id) AS resurrected
FROM clusterAllReplicas('{cluster}', ${ANALYTICS_DB_DATABASE_NAME}.traces)
WHERE (workspace_id, project_id, id) IN (
    SELECT
        workspace_id,
        toFixedString(project_id, 36),
        toFixedString(deleted_id, 36)
    FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
    WHERE source_table = 'traces'
      AND event_time >= toDateTime64('${CUTOVER_START}', 6)
      AND project_id != ''
      AND length(project_id) = 36
      AND length(deleted_id) = 36
)
SETTINGS log_comment = 'traces_local_v2_rollback:verify_replay';
