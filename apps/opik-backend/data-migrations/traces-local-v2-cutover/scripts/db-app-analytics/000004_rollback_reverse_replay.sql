-- runbook traces-local-v2-cutover — ROLLBACK reverse-replay (driven by ../rollback.sh after stage B or C)
--
-- Re-applies the deletes that fired on the successor since cutover_start onto the restored original `traces`, so they do
-- not resurrect. Two branches, like the forward replay in 000002: events with a project match the full key; events from
-- the product's workspace-scoped delete fallback (project_id = '') match (workspace_id, id). Shared by stages B and C
-- (run right after the swap/promote), never on its own. If the set is ever large, bound it with
-- AND toMonday(id_at) = toDate('<week>') and loop the weeks.
--
-- Deliberately NO resurrection guard (the `AND ... NOT IN traces` arm the forward replay in 000002 carries). Do NOT add
-- one here: rollback abandons all post-cutover writes on the successor (they are being discarded) while still honoring
-- post-cutover deletes. `traces` here is the RESTORED ORIGINAL, so a bridged id is present as its pre-cutover version; a
-- liveness guard would spare it and thereby UNDO the user's post-cutover delete (resurrecting stale content). Masking it
-- unconditionally is the correct rollback semantics.
DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
WHERE (workspace_id, project_id, id) IN (
    SELECT
        workspace_id,
        toFixedString(project_id, 36),
        toFixedString(deleted_id, 36)
    FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
    WHERE source_table = 'traces'
      AND event_time >= toDateTime64('${CUTOVER_START}', 6)
      AND project_id != ''
)
OR (workspace_id, id) IN (
    SELECT
        workspace_id,
        toFixedString(deleted_id, 36)
    FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
    WHERE source_table = 'traces'
      AND event_time >= toDateTime64('${CUTOVER_START}', 6)
      AND project_id = ''
)
-- lightweight_deletes_sync = 2: wait for the mutation on every replica so the restored `traces` is consistent
-- cluster-wide before the rollback is declared done (see 000002 for the rationale).
SETTINGS allow_nondeterministic_mutations = 1,
         lightweight_deletes_sync = 2;
