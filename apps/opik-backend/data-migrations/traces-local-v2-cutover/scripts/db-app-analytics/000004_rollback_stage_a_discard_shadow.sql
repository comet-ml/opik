-- runbook traces-local-v2-cutover — ROLLBACK stage A: discard the shadow (driven by ../rollback.sh --stage A)
--
-- Use when the backfill/delta ran but the EXCHANGE did NOT. The live `traces` was never touched by the backfill, so
-- there is nothing to restore; this only discards the disposable shadow copy so a re-attempt starts clean. backfill.sh
-- is idempotent (a re-run skips windows already present), so even this is optional — it just reclaims space.
--
-- SAFETY: this is the only rollback file containing a TRUNCATE, isolated so it can never run alongside the EXCHANGE/DROP
-- of the other stages. It targets `traces_local_v2` (the disposable shadow successor, only ever holds copied-in data
-- pre-EXCHANGE). rollback.sh asserts the pre-EXCHANGE topology (traces = original schema, not Distributed) before
-- running it, so it cannot fire post-EXCHANGE — by which point the old original has been renamed to
-- `traces_pre_cutover_backup` and `traces_local_v2` no longer exists, so there is nothing here that could touch the
-- backup.
--
-- max_table_size_to_drop = 0 disables the drop-size guard for this statement: TRUNCATE is subject to
-- max_table_size_to_drop (default 50 GB) just like DROP, and the shadow is well over that on any instance large enough
-- to need this runbook — without the override the rollback throws exactly when it is needed.
SET log_comment = 'traces_local_v2_rollback:stage_a';
TRUNCATE TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' SETTINGS max_table_size_to_drop = 0;
