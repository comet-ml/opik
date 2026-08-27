-- runbook traces-local-v2-cutover — ROLLBACK reverse-replay POSTCONDITION (driven by ../rollback.sh, after the replay)
-- The gate test TracesLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Counts ids the bridge recorded as deleted since cutover_start that are LIVE again on the restored `traces`. Must be 0;
-- anything else means the reverse replay did not take, and rows a user deleted after the cutover are being served again.
--
-- Why a separate assertion and not an inference from the replay or the fidelity compare: the replay reports that its
-- statement ran, not that the result holds (a lightweight DELETE matching nothing succeeds), and verify.sh windows on
-- `created_at` with the post-rollback compare bounded below the cutover window — so a row created *inside* that window
-- and deleted after it falls outside every window the compare looks at.
--
-- What 0 proves: every delete the bridge RECORDED in the window is masked. What it does not: capture runs after the
-- delete and is best-effort by design (an auxiliary insert must never fail a user's delete), so a delete still in flight
-- when this runs, or one whose capture errored, is invisible to the replay and to this check alike. Quiescing trace
-- deletes before the promote is what bounds that — see the runbook — not this query.
--
-- KEEP IN STEP WITH 000004_rollback_reverse_replay.sql: same (workspace_id, project_id, id) key, same toFixedString(36)
-- casts onto the bridge's String columns, same length guards. A check filtered differently from the replay would either
-- miss what the replay missed or flag rows the replay was never asked to touch. Change one, change both.
--
-- clusterAllReplicas + uniqExact: the mask is per-replica state and the replay waits for every replica
-- (lightweight_deletes_sync = 2), so reading them all makes convergence an observation rather than an assumption. That
-- returns each row once per replica, so only a distinct count keeps the answer meaning "ids live again" — an id masked
-- on one replica but live on another counts once, which is what an operator needs to act on.

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
