-- runbook traces-local-v2-cutover — ROLLBACK un-wrap: remove the Distributed wrap, keep the cutover (driven by
-- ../rollback.sh --unwrap-only)
-- The gate test TracesLocalV2CutoverTest reimplements this inline; keep the two in step (see its Javadoc).
--
-- Use when the WRAP misbehaves but the cutover itself is fine. It reverses only the sharding half: `traces` goes back to
-- being the partitioned successor MergeTree and the `Distributed` wrapper is gone, landing in the post-EXCHANGE,
-- pre-wrap state — a supported resting state the runbook already describes (`--skip-wrap` stops there).
--
-- WHY THIS EXISTS SEPARATELY FROM STAGE C. Stage C is the only other statement that touches the wrap, and it bundles
-- four things: drop the wrapper, promote the parked original, park the successor, reverse-replay. That is the right
-- answer when the SUCCESSOR is suspect, but a disproportionate one when only the routing definition is: the wrapper
-- holds no data, yet stage C abandons the whole backfill, makes post-cutover writes non-live, runs the guard-less
-- reverse replay, reverts to the unpartitioned original, and leaves the sentinel/duration repair to do. This does none
-- of that. Two consequences worth stating:
--   * It needs NO reverse-replay. The successor stays live, so no write is abandoned and no delete needs re-applying —
--     the whole reason stage B/C carry `--cutover-start`, `--confirm-retention-paused` and
--     `--accept-post-cutover-write-loss`. None apply here.
--   * It works AFTER finalize.sh. Stages B and C both require `traces_pre_cutover_backup`, which finalize drops; this
--     needs only `traces` and `traces_local`. Since the documented order is wrap, soak, then finalize, post-wrap +
--     post-finalize is the expected steady state — and until this existed, a wrap fault discovered there had no
--     tooling answer at all.
--
-- SCOPE LIMIT: this undoes SHARDING only. If the partitioned successor itself is the problem — a fidelity defect, a
-- partition-count or merge-load regression, slower queries — un-wrapping changes none of it; use stage B/C (while the
-- parked original still exists) instead. Complement, not replacement.
--
-- GAPLESS per node, by the same construction as the wrap and stage C: a SINGLE atomic multi-target RENAME (all clauses
-- apply or none) moves the data-less wrapper to an explicit temp name and promotes `traces_local` into the name it
-- frees, so `traces` is never absent on a node. ACROSS the shard's replicas ON CLUSTER runs synchronously — the client
-- blocks until every reachable replica applies it, or throws naming a laggard that then converges via the DDL queue —
-- NOT globally atomic, so a sub-second cross-replica skew remains. It is the exact mirror of the wrap's: while a lagging
-- replica still has the wrapper, that wrapper resolves `traces_local`, which the already-renamed replicas no longer
-- have, so a query routed there can fail with UNKNOWN_TABLE (the wrap's own window is the same thing in reverse — a
-- Distributed query reaching a node where `traces_local` does not exist YET). It is brief and fails loudly rather than
-- silently, and ../rollback.sh gates it behind --confirm-maintenance; quiescing reads, not just buffering writes, is
-- what actually covers it.
--
-- Partial-failure recovery: if the RENAME succeeds and the DROP does not, the estate is already correct (`traces` is the
-- successor) and only the data-less ex-wrapper lingers under `traces_dist_old`. Nothing needs re-running — --unwrap-only
-- would (correctly) refuse now that `traces` is no longer Distributed. Just drop the leftover:
--   DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old ON CLUSTER '{cluster}' SYNC;
-- Leaving it in place also blocks the NEXT un-wrap (RENAME cannot overwrite an existing name), which ../rollback.sh
-- pre-checks and reports rather than letting the RENAME fail obscurely.
--
-- BEFORE backends resume: set databaseAnalyticsDataModel.tracesDistributedWrapEnabled=false (OPIK-7455) — the inverse of
-- the flip that enabled the wrap. It is the ONLY flag this reverses: `traceColumnsNonNullable` must stay `true` (the live
-- table keeps the successor's sentinel schema) and `tracesWeeklyPartitionPruningEnabled` stays as it was (it asserts a
-- schema fact — that the live table is the partitioned successor — which un-wrapping preserves). Contrast stage B/C,
-- which revert all three because they restore the unpartitioned original.

-- 1. Gapless un-wrap: rotate both names atomically.
SET log_comment = 'traces_local_v2_rollback:unwrap';
RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_local TO ${ANALYTICS_DB_DATABASE_NAME}.traces
    ON CLUSTER '{cluster}';

-- 2. Drop the ex-wrapper by its unambiguous temp name (data-less Distributed routing definition — no size guard needed).
DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_dist_old ON CLUSTER '{cluster}' SYNC;
