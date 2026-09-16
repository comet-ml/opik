-- runbook spans-local-v2-cutover — ROLLBACK stage C: promote the original back (driven by ../rollback.sh --stage C)
-- The gate test SpansLocalV2CutoverTest reimplements this rollback inline; keep the two in step (see its Javadoc).
--
-- Use when the wrap ran. Post-wrap topology: `spans` is a Distributed wrapper over `spans_local` (successor data);
-- `spans_pre_cutover_backup` parks the original. Promote the original back to `spans` GAPLESSLY with a single
-- multi-target RENAME, atomic PER HOST (all clauses apply or none), that rotates all three names at once: the data-less
-- Distributed wrapper (`spans`) moves to an explicit temp name, the original (`spans_pre_cutover_backup`) becomes live
-- `spans` (the name freed by the first clause), and the successor shard (`spans_local`) parks as
-- `spans_post_rollback_backup` (a retained backup, dropped only by finalize.sh — NOT the disposable `spans_local_v2`
-- shadow) — ending in the canonical state (spans = original live, spans_post_rollback_backup = successor parked). So
-- `spans` is never absent on a node. ACROSS the shard's replicas (production is multi-replica) ON CLUSTER runs
-- synchronously — the client blocks until every reachable replica applies it, or throws naming a laggard that then
-- converges via the DDL queue — NOT globally atomic, so the only exposure is a sub-second cross-replica skew as it
-- propagates, during which a read on a not-yet-renamed replica sees the pre-rollback `spans` (the same accepted
-- ON CLUSTER skew as the wrap; nil on a single replica). Run this in the rollback maintenance moment (see the runbook).
--
-- Then drop the ex-wrapper. It is dropped under `spans_dist_old` — a fresh name that ONLY the data-less wrapper ever
-- occupied — so the DROP cannot hit the original data regardless of per-replica DDL timing (the concern with dropping a
-- name that a data-bearing table previously used).
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect, and asserts the post-wrap topology (spans = Distributed) before running it.
--
-- THIS STAGE APPLIES ONLY TO A WRAPPED ESTATE, WHICH THE DEFAULT WINDOW DOES NOT CREATE, and that is worth stating
-- rather than leaving implicit. OPIK-7799 landed spansDistributedWrapEnabled and SpanDAO's routing, so the wrap it
-- reverses is reachable; the runbook nonetheless defers it while the readiness gap OPIK-7799 left open stands (see the
-- README section of that name, and OPIK-8376). So an estate reaches this stage only after a deliberate --with-wrap or
-- --wrap-only run. The file ships complete so the wrap and its reversal are reviewed together rather than the reversal
-- being authored later, under pressure, against an estate already wrapped; rollback.sh's topology guard refuses this
-- stage cleanly on an unwrapped estate.
--
-- BEFORE backends resume: set databaseAnalyticsDataModel.spansDistributedWrapEnabled=false (OPIK-7799). This stage
-- makes `spans` a MergeTree again and parks `spans_local`, so a still-true flag would send SpanDAO mutations at the
-- missing `spans_local`. It is the inverse of the flip that enabled the wrap.

-- 1. Gapless promote: rotate all three names atomically.
SET log_comment = 'spans_local_v2_rollback:stage_c';
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.spans TO ${ANALYTICS_DB_DATABASE_NAME}.spans_dist_old,
    ${ANALYTICS_DB_DATABASE_NAME}.spans_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.spans,
    ${ANALYTICS_DB_DATABASE_NAME}.spans_local TO ${ANALYTICS_DB_DATABASE_NAME}.spans_post_rollback_backup
    ON CLUSTER '{cluster}';

-- 2. Drop the ex-wrapper by its unambiguous temp name (data-less Distributed routing definition — no size guard needed).
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.spans_dist_old ON CLUSTER '{cluster}' SYNC;
