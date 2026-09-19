-- runbook spans-local-v2-cutover — ROLLBACK reverse-replay POSTCONDITION (driven by ../rollback.sh, after the replay)
-- The gate test SpansLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Counts ids the bridge recorded as deleted since cutover_start that are LIVE again on the restored `spans`. Must be 0;
-- anything else means the reverse replay did not take, and spans a user deleted (by deleting their trace) after the
-- cutover are being served again.
--
-- Why a separate assertion and not an inference from the replay or the fidelity compare: the replay reports that its
-- statement ran, not that the result holds (a lightweight DELETE matching nothing succeeds), and verify.sh windows on
-- `created_at` with the post-rollback compare bounded below the cutover window — so a row created *inside* that window
-- and deleted after it falls outside every window the compare looks at.
--
-- What 0 proves: every delete the bridge RECORDED in the window is masked. What it does not: capture runs before the
-- delete but is best-effort by design (an auxiliary insert must never fail a user's delete), so a delete still in flight
-- when this runs, or one whose capture errored, is invisible to the replay and to this check alike. A delete that merely
-- errored is no longer such a case (OPIK-8141): capture goes first, so it is recorded regardless. Quiescing trace
-- deletes before the promote is what bounds the rest — see the runbook — not this query. Note the quiesce target is
-- TRACE deletes: spans have no standalone delete endpoint, so the cascade is the only path that reaches this bridge.
--
-- KEEP IN STEP WITH 000004_rollback_reverse_replay.sql: same (workspace_id, project_id, id) key, same toFixedString(36)
-- casts onto the bridge's String columns, same length guards. A check filtered differently from the replay would either
-- miss what the replay missed or flag rows the replay was never asked to touch. Change one, change both.
--
-- THE KEY IS THE BRIDGE'S, NOT THE TABLE'S DEDUP KEY, and uniqExact is what makes that sound. spans_local_v2 dedups on
-- (workspace_id, project_id, trace_id, id) and the source on that plus parent_span_id, but the bridge records no
-- trace_id — so this counts distinct (workspace_id, project_id, id) triples, which is the granularity a span delete
-- actually operates at. A span id is unique within a project in every path the product writes, so the triple identifies
-- one span; and where it somehow did not, counting the triple still answers the operator's question ("how many deleted
-- spans are being served again") rather than inflating it by physical version.
--
-- clusterAllReplicas + uniqExact: the mask is per-replica state and the replay waits for every replica
-- (lightweight_deletes_sync = 2), so reading them all makes convergence an observation rather than an assumption. That
-- returns each row once per replica, so only a distinct count keeps the answer meaning "ids live again" — an id masked
-- on one replica but live on another counts once, which is what an operator needs to act on.

SELECT uniqExact(workspace_id, project_id, id) AS resurrected
FROM clusterAllReplicas('{cluster}', ${ANALYTICS_DB_DATABASE_NAME}.spans)
WHERE (workspace_id, project_id, id) IN (
    SELECT
        workspace_id,
        toFixedString(project_id, 36),
        toFixedString(deleted_id, 36)
    FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
    WHERE source_table = 'spans'
      AND event_time >= toDateTime64('${CUTOVER_START}', 6, 'UTC')
      AND project_id != ''
      AND length(project_id) = 36
      AND length(deleted_id) = 36
)
SETTINGS log_comment = 'spans_local_v2_rollback:verify_replay';
