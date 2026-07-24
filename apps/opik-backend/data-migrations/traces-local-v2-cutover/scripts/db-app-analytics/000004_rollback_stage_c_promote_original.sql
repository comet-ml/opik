-- runbook traces-local-v2-cutover — ROLLBACK stage C: promote the original back (driven by ../rollback.sh --stage C)
--
-- Use when the wrap ran. Post-wrap topology: `traces` is a Distributed wrapper over `traces_local` (successor data);
-- `traces_pre_cutover_backup` parks the original. Promote the original back to `traces` GAPLESSLY: EXCHANGE swaps the
-- names atomically, so `traces` becomes the original MergeTree with no window where the name is absent (unlike a
-- DROP-then-RENAME). The wrapper (now parked under the backup name) holds no data, so it is dropped next, and the
-- successor shard is parked back under `traces_local_v2` — ending in the canonical state (traces = original live,
-- traces_local_v2 = successor parked), with no leftover names.
--
-- rollback.sh runs the reverse-replay (000004_rollback_reverse_replay.sql) right after this so deletes since
-- cutover_start do not resurrect, and asserts the post-wrap topology (traces = Distributed) before running it.

-- 1. Gapless promote: `traces` immediately becomes the original MergeTree; the Distributed wrapper moves to the backup name.
EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}';

-- 2. Drop the now data-less Distributed wrapper (routing definition only — no size guard needed).
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}' SYNC;

-- 3. Park the successor shard back under its canonical name.
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';
