-- runbook traces-local-v2-cutover — ROLLBACK sentinel repair (driven by ../rollback.sh --sentinel-repair-only)
-- The gate test TracesLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Restores NULL on the restored ORIGINAL `traces` for rows written while traceColumnsNonNullable was true. The successor
-- schema stores an absent end_time/ttft as a sentinel (epoch / NaN) where the original stores NULL, so rows that landed
-- through the flag read back as "ended at 1970" / "ttft NaN" once the promote makes the original live again — and the
-- original's MATERIALIZED duration expression computed a large NEGATIVE duration from them. That expression does guard
-- against the epoch, but on `start_time` only: `end_time` it checks for NULL alone, which a sentinel is not.
--
-- MATERIALIZE COLUMN duration would NOT fix that: it re-evaluates the same expression against the same sentinel. Only
-- restoring NULL does, and the mutation recomputes duration as a side effect of rewriting the row.
--
-- NOT part of stage B/C, deliberately. The flag revert has to land on every backend FIRST or in-flight writes keep
-- minting sentinels behind the repair, and rolling out config is not something these DB-facing scripts do. So this is a
-- separate, later invocation, gated on the operator asserting the revert is live (--confirm-flag-reverted).
--
-- ONE ALTER carrying TWO commands, on purpose: neither predicate is on the primary key, so ClickHouse cannot prune parts
-- and a mutation rewrites every one of them. Combining the commands into a single mutation halves that to one pass, which
-- on a large table is the difference that matters in a rollback tail. The cost is atomicity — it needs ALTER UPDATE on
-- BOTH columns and applies neither if one grant is missing (../rollback.sh translates the ACCESS_DENIED).
--
-- LIMITATION, inherent rather than an omission: an end_time of exactly epoch cannot be told from the sentinel, nor a
-- genuine NaN ttft from the sentinel NaN — the successor schema uses those very values to MEAN "absent", so the
-- distinction does not exist in the data to recover. What bounds the blast radius is the topology guard in
-- ../rollback.sh (this runs only against a restored original with the successor parked) and the counts in
-- 000004_rollback_verify_sentinels.sql, not this statement.
--
-- KEEP IN STEP WITH 000004_rollback_verify_sentinels.sql: same two predicates, same DateTime64 precision 9 (the
-- original's end_time is nanosecond). A check filtered differently from the repair would clear while sentinels remain,
-- or never clear at all. Change one, change both.
-- Deliberately NOT `ON CLUSTER`, matching the reverse replay beside it. `traces` is a Replicated*MergeTree, so a
-- mutation entered on one replica reaches the others through the replication log; routing it through the distributed-DDL
-- queue instead would add a second, unrelated wait bounded by `distributed_ddl_task_timeout` (180s by default). This
-- mutation rewrites every part, so on a large table that bound is exceeded routinely, and with the default
-- `distributed_ddl_output_mode = 'throw'` the client raises TIMEOUT_EXCEEDED while the mutation is progressing normally.
-- The driver would then report a healthy repair as failed. Single-shard assumption, same as the reverse replay's.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    UPDATE end_time = NULL WHERE end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC'),
    UPDATE ttft = NULL WHERE isNaN(ttft)
-- mutations_sync = 2: wait for the mutation on every replica, so the repair has converged cluster-wide before the
-- postcondition reads it back (same rationale as lightweight_deletes_sync = 2 in the reverse replay). The wait is
-- unbounded server-side, so the CLIENT socket timeout is what limits it — see rollback.sh --receive-timeout.
SETTINGS mutations_sync = 2,
         log_comment = 'traces_local_v2_rollback:sentinel_repair';
