-- runbook traces-local-v2-cutover — ROLLBACK sentinel repair (driven by ../rollback.sh --sentinel-repair-only)
-- The gate test TracesLocalV2CutoverTest reimplements this statement inline; keep the two in step (see its Javadoc).
--
-- Restores NULL on rows written into the still-Nullable original while traceColumnsNonNullable was true. The successor
-- schema stores an absent end_time/ttft as a sentinel (epoch / NaN) where the original stores NULL, so rows that landed
-- through the flag read back as "ended at 1970" / "ttft NaN" once the promote makes the original live again — and the
-- original's MATERIALIZED duration computed a large NEGATIVE duration from them. That expression does guard against the
-- epoch, but on `start_time` only: `end_time` it checks for NULL alone, which a sentinel is not.
--
-- MATERIALIZE COLUMN duration would NOT fix that: it re-evaluates the same expression against the same sentinel. Only
-- restoring NULL does, and the mutation recomputes duration as a side effect of rewriting the row.
--
-- THE WINDOW IS MANDATORY, AND IS THE ONLY THING THAT MAKES THIS SAFE. An epoch end_time is not by itself evidence the
-- flag produced it: a client can send one, and rows that predate the flag entirely do. Repairing every match would set
-- those to NULL and they could not be recovered — the parked successor encodes an absent end_time as the same epoch, so
-- there is no reference copy to restore from, and the counts would still report success because no sentinel would
-- remain. Measured on an internal environment: the unbounded predicate matched 34 keys across 12 workspaces where only
-- 5 came from the flag window; the other 29 carried genuine client-sent values.
--
-- So the operator supplies the window the flag was live in, and only rows written inside it are touched. Both arms are
-- needed, for the same reason the delta insert needs both: a row CREATED in the window is caught by created_at, and a
-- pre-existing row UPDATED in the window — which is where its sentinel came from — is caught by last_updated_at.
--
-- NOT part of stage B/C, deliberately. The flag revert has to land on every backend FIRST or in-flight writes keep
-- minting sentinels behind the repair, and rolling out config is not something these DB-facing scripts do.
--
-- ONE ALTER carrying TWO commands, on purpose: neither predicate is on the primary key, so ClickHouse cannot prune parts
-- and a mutation rewrites every one of them. Combining the commands into a single mutation halves that to one pass. The
-- cost is atomicity — it needs ALTER UPDATE on BOTH columns and applies neither if one grant is missing (../rollback.sh
-- translates the ACCESS_DENIED).
--
-- Deliberately NOT `ON CLUSTER`, matching the reverse replay beside it. `traces` is a Replicated*MergeTree, so a
-- mutation entered on one replica reaches the others through the replication log; routing it through the distributed-DDL
-- queue instead would add a second wait bounded by `distributed_ddl_task_timeout` (180s by default), which this
-- statement exceeds routinely — and with the default `distributed_ddl_output_mode = 'throw'` the client would raise
-- TIMEOUT_EXCEEDED while the mutation progressed normally. Single-shard assumption, same as the reverse replay's.
--
-- KEEP IN STEP WITH 000004_rollback_verify_sentinels.sql: same two predicates, same window on the same two columns, same
-- DateTime64 precisions. A check scoped differently from the repair either clears while sentinels remain, or never
-- clears at all.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces
    UPDATE end_time = NULL
        WHERE end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')
          AND (   (created_at      >= toDateTime64('${SENTINEL_WINDOW_FROM}', 6, 'UTC')
               AND created_at      <  toDateTime64('${SENTINEL_WINDOW_TO}', 6, 'UTC'))
               OR (last_updated_at >= toDateTime64('${SENTINEL_WINDOW_FROM}', 6, 'UTC')
               AND last_updated_at <  toDateTime64('${SENTINEL_WINDOW_TO}', 6, 'UTC'))),
    UPDATE ttft = NULL
        WHERE isNaN(ttft)
          AND (   (created_at      >= toDateTime64('${SENTINEL_WINDOW_FROM}', 6, 'UTC')
               AND created_at      <  toDateTime64('${SENTINEL_WINDOW_TO}', 6, 'UTC'))
               OR (last_updated_at >= toDateTime64('${SENTINEL_WINDOW_FROM}', 6, 'UTC')
               AND last_updated_at <  toDateTime64('${SENTINEL_WINDOW_TO}', 6, 'UTC')))
-- mutations_sync = 2: wait for the mutation on every replica, so the repair has converged cluster-wide before the
-- postcondition reads it back (same rationale as lightweight_deletes_sync = 2 in the reverse replay). The wait is
-- unbounded server-side, so the CLIENT socket timeout is what limits it — see rollback.sh --receive-timeout.
SETTINGS mutations_sync = 2,
         log_comment = 'traces_local_v2_rollback:sentinel_repair';
