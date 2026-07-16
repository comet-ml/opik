-- runbook traces-local-v2-cutover — ROLLBACK stage C: promote the original back (driven by ../rollback.sh --stage C)
--
-- Use when the wrap ran. Post-wrap topology: `traces` is a Distributed wrapper over `traces_local` (successor data);
-- `traces_pre_cutover_backup` parks the original. Drop the wrapper (it stores no data — a routing definition only),
-- then in one atomic RENAME promote the original back to `traces` and park the successor data back under
-- `traces_local_v2` — ending in the canonical state (traces = original live, traces_local_v2 = successor parked), with
-- no leftover names.
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect, and asserts the post-wrap topology (traces = Distributed) before running it.
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' SYNC;

RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_local TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2
    ON CLUSTER '{cluster}';
