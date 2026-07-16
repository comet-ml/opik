-- runbook traces-local-v2-cutover — step 3 of 3: EXCHANGE + Distributed wrap (reference statements)
--
-- ../exchange_and_wrap.sh drives this: it records cutover_start, runs the `exchange` block, and (unless --skip-wrap)
-- the `wrap` block. Run it right after step 2's delta + replay, while the async-insert buffer is still holding writes.
-- Do NOT run this whole file wholesale — the driver runs one marked block at a time. Buffer knob: raise
-- databaseAnalytics.asyncInsertBusyTimeoutMaxMs before the cutover and unset it after (a backend-config action, not SQL).
--
-- cutover_start is a now64(6) captured RIGHT BEFORE the EXCHANGE; a rollback after this point replays deletes that fired
-- on the new live table since then. exchange_and_wrap.sh captures and prints it; record it for the rollback.

-- >>> BEGIN exchange
-- The atomic swap: `traces` now refers to the partitioned data. The displaced old data lands under `traces_local_v2`
-- momentarily, then is renamed to `traces_pre_cutover_backup` so its name marks it as the retained pre-cutover backup,
-- not the "v2" successor (rationale: README "Naming and the parked backup"). Requires an Atomic database (default). If
-- the Liquibase ClickHouse extension cannot execute EXCHANGE ON CLUSTER in the downtime-based path, use the fallback
-- RENAME sequence in the README instead.
EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';

RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 TO ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}';
-- >>> END exchange

-- >>> BEGIN wrap
-- Sharding-ready wrap: rename the partitioned table to *_local and front it with a Distributed table keyed on
-- sipHash64(project_id). Transparent on a single shard; switching on sharding later is config-only. The {cluster} macro
-- (not the literal 'cluster') keeps the DDL portable; it is resolved server-side.
-- HARD PREREQUISITE: a Distributed table supports SELECT and INSERT but NOT mutations — a lightweight DELETE returns
-- "DELETE query is not supported" (code 36) and ALTER ... DELETE returns "Distributed doesn't support mutations"
-- (code 48). So the product's delete-by-id AND retention deletes both break the moment this wrap is applied. Do NOT run
-- the wrap until those DAO paths target `traces_local` (see README "The Distributed wrap"). The EXCHANGE above is the
-- data cutover and leaves `traces` a MergeTree where deletes still work; the wrap is a separate, gated step.
RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local ON CLUSTER '{cluster}';

CREATE TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' AS ${ANALYTICS_DB_DATABASE_NAME}.traces_local
    ENGINE = Distributed('{cluster}', '${ANALYTICS_DB_DATABASE_NAME}', 'traces_local', sipHash64(project_id));
-- >>> END wrap

-- After the wrap: restore the buffer ceiling (unset asyncInsertBusyTimeoutMaxMs), verify (README "Verifying the
-- migration"), and keep `traces_pre_cutover_backup` (the parked old data) until the soak completes. Rollback: the
-- 000004_rollback_* files via ../rollback.sh.
