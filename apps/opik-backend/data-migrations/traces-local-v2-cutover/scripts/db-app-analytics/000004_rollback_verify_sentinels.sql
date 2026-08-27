-- runbook traces-local-v2-cutover — ROLLBACK sentinel-repair COUNTS (driven by ../rollback.sh, before and after the repair)
-- The gate test TracesLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Read-only, and read TWICE by the driver: before the repair, to report the size of the problem and to skip the mutation
-- entirely when there is none; after it, as the postcondition. Safe to run on its own at any point.
--
-- THE GATE IS sentinel_end_time AND sentinel_ttft REACHING 0 — nothing else. `negative_duration_total`, which earlier
-- revisions of this procedure printed, is deliberately ABSENT: rows whose end_time genuinely precedes start_time are a
-- pre-existing source artifact that this repair does not address and does not claim to, so that number never reaches 0
-- and reporting it invites an operator to read a healthy repair as a failed one. `negative_from_sentinel` is kept because
-- it is bounded by the sentinel set and answers a question the operator does have before repairing — how many rows are
-- currently serving a bogus duration; it is implied 0 once the two gates are, and is not itself a gate.
--
-- What 0 proves: no row on any replica still carries either sentinel, so nothing reads back as "ended at 1970" and no
-- duration is negative *because of* the flip. What it does not prove: that every such row was found — a write that
-- lands after this reads has not been counted, which is why the flag revert must be live cluster-wide first
-- (--confirm-flag-reverted) rather than being something this query can establish.
--
-- clusterAllReplicas + uniqExact, for the same two reasons as 000004_rollback_verify_replay.sql: the repair waits for
-- every replica (mutations_sync = 2), so reading them all makes convergence an observation rather than an assumption;
-- and that returns each row once per replica, so only a distinct count keeps the answer meaning "rows affected".
--
-- No FINAL, on purpose. The mutation rewrites every physical row that matches, superseded ReplacingMergeTree versions
-- included, so the check has to see them too: FINAL would hide a stale version still carrying a sentinel behind a clean
-- newer one, and that version is what a non-FINAL read of this table would serve.
--
-- KEEP IN STEP WITH 000004_rollback_sentinel_repair.sql: same two predicates, same DateTime64 precision 9.

SELECT
    uniqExactIf((workspace_id, project_id, id), end_time = toDateTime64('1970-01-01 00:00:00', 9)) AS sentinel_end_time,
    uniqExactIf((workspace_id, project_id, id), isNaN(ttft)) AS sentinel_ttft,
    uniqExactIf((workspace_id, project_id, id),
                duration < 0 AND end_time = toDateTime64('1970-01-01 00:00:00', 9)) AS negative_from_sentinel
FROM clusterAllReplicas('{cluster}', ${ANALYTICS_DB_DATABASE_NAME}.traces)
SETTINGS log_comment = 'traces_local_v2_rollback:verify_sentinels';
