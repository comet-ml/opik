-- runbook traces-local-v2-cutover — ROLLBACK stage B: swap the tables back (driven by ../rollback.sh --stage B)
--
-- Use when the EXCHANGE ran but the wrap did NOT. `traces` holds the successor data and `traces_pre_cutover_backup`
-- parks the original. A SINGLE atomic multi-target RENAME rotates both names back: the successor (`traces`) returns to
-- `traces_local_v2`, and the original (`traces_pre_cutover_backup`) returns to `traces` (the name freed by the first
-- clause) — restoring the canonical state (traces = original live, traces_local_v2 = successor parked), identical to
-- pre-EXCHANGE. Gapless and with no orphan risk: because it is one atomic statement, there is no window where a partial
-- failure could strand the successor under the backup name (the flaw of a separate EXCHANGE + RENAME). Non-destructive.
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect. rollback.sh asserts the post-EXCHANGE, pre-wrap topology (traces = successor schema,
-- not Distributed) before running it.
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup TO ${ANALYTICS_DB_DATABASE_NAME}.traces
    ON CLUSTER '{cluster}';
