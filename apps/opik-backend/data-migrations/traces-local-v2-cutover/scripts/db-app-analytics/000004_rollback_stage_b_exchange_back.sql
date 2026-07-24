-- runbook traces-local-v2-cutover — ROLLBACK stage B: swap the tables back (driven by ../rollback.sh --stage B)
--
-- Use when the EXCHANGE ran but the wrap did NOT. `traces` holds the successor data and `traces_pre_cutover_backup`
-- parks the original. Swap them back (so `traces` is the original live again), then rename the now-parked successor
-- back to `traces_local_v2` — restoring the canonical state (traces = original live, traces_local_v2 = successor
-- parked), identical to pre-EXCHANGE. Non-destructive. rollback.sh runs the reverse-replay
-- (000004_rollback_reverse_replay.sql) right after this so deletes since cutover_start do not resurrect. rollback.sh
-- asserts the post-EXCHANGE, pre-wrap topology (traces = successor schema, not Distributed) before running it.
--
-- These two statements are NOT jointly atomic (unlike stage C's single multi-target RENAME). The EXCHANGE already makes
-- live `traces` the original again; if the following RENAME fails (e.g. a transient per-replica ON CLUSTER DDL error),
-- the abandoned successor is left under `traces_pre_cutover_backup` instead of the canonical `traces_local_v2`, and
-- rollback.sh cannot re-drive it (stage B/A both abort on that split state). RECOVERY: live data is already correct;
-- only the relabel remains — run it manually:
--   RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';
EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}';

RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';
