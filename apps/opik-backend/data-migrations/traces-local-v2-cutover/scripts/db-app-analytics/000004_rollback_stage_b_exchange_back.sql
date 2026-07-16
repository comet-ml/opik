-- runbook traces-local-v2-cutover — ROLLBACK stage B: swap the tables back (driven by ../rollback.sh --stage B)
--
-- Use when the EXCHANGE ran but the wrap did NOT. `traces` holds the successor data and `traces_pre_cutover_backup`
-- parks the original. Swap them back (so `traces` is the original live again), then rename the now-parked successor
-- back to `traces_local_v2` — restoring the canonical state (traces = original live, traces_local_v2 = successor
-- parked), identical to pre-EXCHANGE. Non-destructive. rollback.sh runs the reverse-replay
-- (000004_rollback_reverse_replay.sql) right after this so deletes since cutover_start do not resurrect. rollback.sh
-- asserts the post-EXCHANGE, pre-wrap topology (traces = successor schema, not Distributed) before running it.
EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}';

RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';
