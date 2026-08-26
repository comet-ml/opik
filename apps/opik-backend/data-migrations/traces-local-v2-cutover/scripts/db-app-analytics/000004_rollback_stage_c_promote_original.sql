-- runbook traces-local-v2-cutover — ROLLBACK stage C: promote the original back (driven by ../rollback.sh --stage C)
-- The gate test TracesLocalV2CutoverTest reimplements this rollback inline; keep the two in step (see its Javadoc).
--
-- Use when the wrap ran. Post-wrap topology: `traces` is a Distributed wrapper over `traces_local` (successor data);
-- `traces_pre_cutover_backup` parks the original. Promote the original back to `traces` GAPLESSLY with a single
-- multi-target RENAME, atomic PER HOST (all clauses apply or none), that rotates all three names at once: the data-less
-- Distributed wrapper (`traces`) moves to an explicit temp name, the original (`traces_pre_cutover_backup`) becomes live
-- `traces` (the name freed by the first clause), and the successor shard (`traces_local`) parks as
-- `traces_post_rollback_backup` (a retained backup, dropped only by finalize.sh — NOT the disposable `traces_local_v2`
-- shadow) — ending in the canonical state (traces = original live, traces_post_rollback_backup = successor parked). So
-- `traces` is never absent on a node. ACROSS the shard's replicas (production is multi-replica) ON CLUSTER runs
-- synchronously — the client blocks until every reachable replica applies it, or throws naming a laggard that then
-- converges via the DDL queue — NOT globally atomic, so the only exposure is a sub-second cross-replica skew as it
-- propagates, during which a read on a not-yet-renamed replica sees the pre-rollback `traces` (the same accepted
-- ON CLUSTER skew as the wrap; nil on a single replica). Run this in the rollback maintenance moment (see the runbook).
--
-- Then drop the ex-wrapper. It is dropped under `traces_dist_old` — a fresh name that ONLY the data-less wrapper ever
-- occupied — so the DROP cannot hit the original data regardless of per-replica DDL timing (the concern with dropping a
-- name that a data-bearing table previously used).
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect, and asserts the post-wrap topology (traces = Distributed) before running it.
--
-- BEFORE backends resume: set databaseAnalyticsDataModel.tracesDistributedWrapEnabled=false (OPIK-7455). This stage
-- makes `traces` a MergeTree again and parks `traces_local`, so a still-true flag would send TraceDAO mutations at the
-- missing `traces_local`. It is the inverse of the flip that enabled the wrap.

-- 1. Gapless promote: rotate all three names atomically.
SET log_comment = 'traces_local_v2_rollback:stage_c';
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_local TO ${ANALYTICS_DB_DATABASE_NAME}.traces_post_rollback_backup
    ON CLUSTER '{cluster}';

-- 2. Drop the ex-wrapper by its unambiguous temp name (data-less Distributed routing definition — no size guard needed).
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old ON CLUSTER '{cluster}' SYNC;
