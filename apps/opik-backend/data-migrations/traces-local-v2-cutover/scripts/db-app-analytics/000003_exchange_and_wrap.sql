-- runbook traces-local-v2-cutover — step 3 of 3: replication-settle gate + EXCHANGE + Distributed wrap
-- The gate test TracesLocalV2CutoverTest reimplements the EXCHANGE and wrap statements inline; keep the two in step
-- (see its Javadoc). The settle-gate blocks are read-only and are exercised in the cutover rehearsal instead.
--
-- ../exchange_and_wrap.sh drives this: it runs the settle gate, records cutover_start, runs the `exchange` block, and
-- runs the `wrap` block only with --with-wrap (or --wrap-only, which runs that block alone). Run it right after step 2's
-- delta + replay, so the final-delta -> EXCHANGE gap stays small. Do NOT run this whole file wholesale — the driver runs one marked block at a time. Nothing here needs an
-- ingestion-side config change: the EXCHANGE is atomic per node, so a concurrent insert always commits to a valid
-- table. Writes that land in the old one — in that gap or during the cross-node skew — stay in the parked backup: the
-- open tail write-gap tracked as OPIK-8238, stated in the runbook's "The final cutover window".
--
-- cutover_start is a now64(6) captured RIGHT BEFORE the EXCHANGE; a rollback after this point replays deletes that fired
-- on the new live table since then. exchange_and_wrap.sh captures and prints it; record it for the rollback.

-- Blocks, in the order the driver runs them: settle-sample, then settle-queue-detail / settle-mutation-detail only when
-- the gate refuses, then `exchange`, then `wrap`. The first three are read-only. '{cluster}' is the macro, resolved
-- server-side, as in the 000004 postcondition files.

-- The gate's one sample, as seven numbers on a single row so the driver can read it as TSV: the replication queue's
-- depth, its oldest entry's age in seconds, its highest num_tries and how many of its entries carry a last_exception;
-- then the count of unfinished mutations on the shadow, their oldest age and how many carry a latest_fail_reason. Every
-- column is numeric on purpose — the free-text exception columns would need quoting to survive TSV, and the two detail
-- blocks below print them instead. max()/countIf() over an empty set yield 0, so a drained queue and an idle mutation
-- list need no special casing. Each side is a single-row aggregate, so the CROSS JOIN is 1x1.
--
-- The queue side counts only GET_PART / ATTACH_PART, the entry types that mean a replica does not yet hold a part —
-- which is the one queue condition that would make it serve an incomplete table after the swap. The queue also carries
-- MERGE_PARTS and MUTATE_PART entries, and those say nothing about completeness: a pending merge is an optimisation,
-- and an unfinished mutation is the other half of this sample. Counting them would fail the gate on ordinary post-
-- backfill merge activity, where one legitimate large merge easily outlives the driver's stuck threshold.
-- >>> BEGIN settle-sample
SELECT q.cnt, q.age, q.tries, q.exc, m.cnt, m.age, m.fails
FROM (
    SELECT count()                                     AS cnt,
           max(dateDiff('second', create_time, now())) AS age,
           max(num_tries)                              AS tries,
           countIf(last_exception != '')               AS exc
    FROM clusterAllReplicas('{cluster}', system.replication_queue)
    WHERE database = '${ANALYTICS_DB_DATABASE_NAME}'
      AND table IN ('traces', 'traces_local_v2')
      AND type IN ('GET_PART', 'ATTACH_PART')
) AS q
CROSS JOIN (
    SELECT count()                                     AS cnt,
           max(dateDiff('second', create_time, now())) AS age,
           countIf(latest_fail_reason != '')           AS fails
    FROM clusterAllReplicas('{cluster}', system.mutations)
    WHERE database = '${ANALYTICS_DB_DATABASE_NAME}'
      AND table = 'traces_local_v2'
      AND is_done = 0
) AS m;
-- >>> END settle-sample

-- Printed when the gate judges a replica to be lagging rather than merely busy, with the free text the sample above
-- deliberately omits. Same type filter as the sample, so the rows shown are the population the verdict was reached on.
--
-- Selected by RANK PER TRIGGER rather than a top-N, because the driver fails on any of three independent conditions —
-- an entry older than its age threshold, one past its retry threshold, or any entry carrying a last_exception — and a
-- single ordering cannot surface all three. Sorting by num_tries hides an old-but-rarely-retried entry behind busier
-- ones, which is precisely the entry an age verdict is about. So each replica contributes its oldest entry, its
-- most-retried entry, and up to two carrying an exception: at most four rows, always including whichever tripped the
-- verdict. Ranking per replica also keeps one noisy replica from crowding the others out of the report.
-- >>> BEGIN settle-queue-detail
SELECT replica,
       table,
       type,
       create_time,
       age_seconds,
       num_tries,
       num_postponed,
       postpone_reason,
       last_exception
FROM (
    SELECT hostName()                             AS replica,
           table,
           type,
           create_time,
           dateDiff('second', create_time, now()) AS age_seconds,
           num_tries,
           num_postponed,
           postpone_reason,
           last_exception,
           row_number() OVER (PARTITION BY hostName() ORDER BY create_time ASC)                          AS oldest_rank,
           row_number() OVER (PARTITION BY hostName() ORDER BY num_tries DESC)                           AS retried_rank,
           row_number() OVER (PARTITION BY hostName() ORDER BY (last_exception != '') DESC, create_time) AS failing_rank
    FROM clusterAllReplicas('{cluster}', system.replication_queue)
    WHERE database = '${ANALYTICS_DB_DATABASE_NAME}'
      AND table IN ('traces', 'traces_local_v2')
      AND type IN ('GET_PART', 'ATTACH_PART')
)
WHERE oldest_rank = 1
   OR retried_rank = 1
   OR (last_exception != '' AND failing_rank <= 2)
ORDER BY replica, age_seconds DESC;
-- >>> END settle-queue-detail

-- Printed when a mutation on the shadow has not finished on every replica by the gate's deadline. Bounded per replica
-- for the same reason as the queue detail above: the point is to name which replicas are behind, and a global cap
-- would hide them behind whichever replica sorted first. Every row here is unfinished, so there is no equivalent of
-- that block's decoy problem — only the ordering matters, and one carrying a latest_fail_reason explains more than an
-- older one that is merely still running, so those come first.
-- >>> BEGIN settle-mutation-detail
SELECT hostName() AS replica,
       mutation_id,
       command,
       create_time,
       parts_to_do,
       latest_failed_part,
       latest_fail_reason
FROM clusterAllReplicas('{cluster}', system.mutations)
WHERE database = '${ANALYTICS_DB_DATABASE_NAME}'
  AND table = 'traces_local_v2'
  AND is_done = 0
ORDER BY (latest_fail_reason != '') DESC, create_time
LIMIT 2 BY replica;
-- >>> END settle-mutation-detail

-- >>> BEGIN exchange
-- The atomic swap: `traces` now refers to the partitioned data. The displaced old data lands under `traces_local_v2`
-- momentarily, then is renamed to `traces_pre_cutover_backup` so its name marks it as the retained pre-cutover backup,
-- not the "v2" successor (rationale: README "Naming and the parked backup"). Requires an Atomic database (default). If
-- the Liquibase ClickHouse extension cannot execute EXCHANGE ON CLUSTER in the downtime-based path, use the fallback
-- RENAME sequence in the README instead.
-- log_comment tags these DDL statements in system.query_log for cutover attribution (DDL takes it via a leading SET,
-- not a trailing SETTINGS clause).
SET log_comment = 'traces_local_v2_cutover:exchange';
EXCHANGE TABLES ${ANALYTICS_DB_DATABASE_NAME}.traces AND ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}';

RENAME TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 TO ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup ON CLUSTER '{cluster}';
-- >>> END exchange

-- >>> BEGIN wrap
-- Sharding-ready wrap: move the partitioned table under *_local and front it with a Distributed table keyed on
-- sipHash64(project_id). Transparent on a single shard; switching on sharding later is config-only. The {cluster} macro
-- (not the literal 'cluster') keeps the DDL portable; it is resolved server-side.
-- HARD PREREQUISITE: a Distributed table supports SELECT and INSERT but NOT mutations — a lightweight DELETE returns
-- "DELETE query is not supported" (code 36) and ALTER ... DELETE returns "Distributed doesn't support mutations"
-- (code 48). So the product's delete-by-id AND retention deletes both break the moment this wrap is applied. Do NOT run
-- the wrap until the DAO is retargeted: set backend config databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true
-- (OPIK-7455) in lockstep with the wrap so TraceDAO mutations run against `traces_local` (see the README's
-- "HARD PREREQUISITE for the wrap" note). The EXCHANGE above is the data cutover and leaves `traces` a MergeTree where
-- deletes still work; the wrap is a separate, gated step.
--
-- GAPLESS per node: build the Distributed wrapper under a temp name FIRST (its 'traces_local' target need not exist
-- yet — Distributed resolves it lazily), then a SINGLE atomic multi-target RENAME rotates the data to `traces_local`
-- and the wrapper into `traces` (the name freed by the first clause). So `traces` transitions MergeTree->Distributed
-- with no window where the name is absent — unlike a RENAME-then-CREATE, which leaves `traces` missing in between.
-- (A cross-node ON CLUSTER propagation skew still exists, as for any ON CLUSTER DDL; the driver's --confirm-maintenance
-- gate covers it.) Partial-failure recovery: if the RENAME fails after the CREATE, `traces` is untouched (still the
-- successor MergeTree, live) and only the temp wrapper lingers — drop it and retry:
--   DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.traces_dist ON CLUSTER '{cluster}' SYNC;
SET log_comment = 'traces_local_v2_cutover:wrap';
CREATE TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_dist ON CLUSTER '{cluster}' AS ${ANALYTICS_DB_DATABASE_NAME}.traces
    ENGINE = Distributed('{cluster}', '${ANALYTICS_DB_DATABASE_NAME}', 'traces_local', sipHash64(project_id));

RENAME TABLE
    ${ANALYTICS_DB_DATABASE_NAME}.traces TO ${ANALYTICS_DB_DATABASE_NAME}.traces_local,
    ${ANALYTICS_DB_DATABASE_NAME}.traces_dist TO ${ANALYTICS_DB_DATABASE_NAME}.traces
    ON CLUSTER '{cluster}';
-- >>> END wrap

-- After the wrap: size the tail write-gap (README "The final cutover window"), verify (README "Verifying the
-- migration"), and keep `traces_pre_cutover_backup` (the parked old data) until the soak completes — it is the only
-- copy of the gap rows. Rollback: the 000004_rollback_* files via ../rollback.sh.
