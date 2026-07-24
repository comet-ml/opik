-- runbook traces-local-v2-cutover — ROLLBACK stage C: promote the original back (driven by ../rollback.sh --stage C)
--
-- Use when the wrap ran. Post-wrap topology: `traces` is a Distributed wrapper over `traces_local` (successor data);
-- `traces_pre_cutover_backup` parks the original. Promote the original back to `traces` GAPLESSLY with a single atomic
-- multi-target RENAME that rotates all three names at once: the data-less Distributed wrapper (`traces`) moves to an
-- explicit temp name, the original (`traces_pre_cutover_backup`) becomes live `traces` (the name freed by the first
-- clause), and the successor shard (`traces_local`) parks back under `traces_local_v2` — ending in the canonical state
-- (traces = original live, traces_local_v2 = successor parked). `traces` is never absent on a node.
--
-- Then drop the ex-wrapper. It is dropped under `traces_dist_old` — a fresh name that ONLY the data-less wrapper ever
-- occupied — so the DROP cannot hit the original data regardless of per-replica DDL timing (the concern with dropping a
-- name that a data-bearing table previously used).
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect, and asserts the post-wrap topology (traces = Distributed) before running it.

-- 1. Gapless promote: rotate all three names atomically.
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_local TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2
    ON CLUSTER '{cluster}';

-- 2. Drop the ex-wrapper by its unambiguous temp name (data-less Distributed routing definition — no size guard needed).
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old ON CLUSTER '{cluster}' SYNC;
