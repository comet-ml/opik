#!/usr/bin/env bash
#
# Driver for rolling the buffered traces cutover back (runbook: ../README.md).
#
# Runs the db-app-analytics/000004_rollback_* file(s) that match how far the cutover got. Pick the stage by the last
# step that completed:
#   --stage A   backfill/delta ran but the EXCHANGE did not — discard the shadow (live `traces` is untouched).
#   --stage B   the EXCHANGE ran but not the wrap — swap the tables back, then reverse-replay.
#   --stage C   the wrap ran — drop the wrapper, promote the parked original, then reverse-replay.
# Or --unwrap-only: reverse ONLY the Distributed wrap, keeping the cutover. Use it when the wrap misbehaves but the
# partitioned successor is fine — it lands in the post-EXCHANGE, pre-wrap state (where `--skip-wrap` stops), so no write
# is abandoned, no reverse-replay is needed, and no sentinel repair follows. Unlike stages B/C it does not need the
# parked original, so it is the only wrap recovery left once finalize.sh has dropped it. It undoes SHARDING only: if the
# successor itself is suspect, use stage B/C instead. See 000004_rollback_unwrap.sql.
# Or --reverse-replay-only: re-apply just the reverse deletion replay against the current live `traces`. Use it when a
# stage B/C run's promote succeeded but its reverse-replay was interrupted — the promote leaves `traces` in the restored
# canonical shape, so re-running the stage is (correctly) rejected by the topology guard, which would otherwise strand
# the post-cutover deletes unreplayed and let them resurrect. The replay is idempotent, so this is always safe to re-run.
# Stages B and C need --cutover-start (printed by exchange_and_wrap.sh) to bound the reverse-replay,
# --confirm-retention-paused (retention deletes bypass the bridge, so a retention sweep in the rollback window would
# resurrect a deleted row from the backup), and --accept-post-cutover-write-loss (see below). Keep the deletion bridge
# enabled through the rollback so no delete is lost.
#
# POST-CUTOVER WRITES: stages B/C promote the frozen pre-cutover backup back to live `traces`, so traces WRITTEN to the
# successor after cutover_start stop being live. They are NOT destroyed — the successor is parked as
# traces_post_rollback_backup and retained until finalize.sh, so they can be recovered from there during the soak — but
# the live table no longer serves them. This is inherent to promoting a point-in-time backup and cannot be "fixed"
# (auto-merging the successor's writes would re-import the very data the rollback is discarding);
# --accept-post-cutover-write-loss makes the operator acknowledge it before the promote.
#
# SAFETY: the stages are mutually exclusive and each lives in its OWN file, so no single file mixes a TRUNCATE with an
# EXCHANGE/DROP — running any file does exactly one stage. Before running, this asserts the live `traces` topology matches
# the requested stage (or mode) and aborts otherwise, so a wrong-stage run cannot destroy data. No data-bearing table is dropped.
# Stages B/C end with traces = original data live and the successor parked as traces_post_rollback_backup (a retained
# backup that finalize.sh later recycles into an empty traces_local_v2); stage A discards the empty traces_local_v2
# shadow and leaves traces untouched.
#
# Connection: CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment, plus --host and --port. CLICKHOUSE_PORT is
# NOT honored by clickhouse-client, and CLICKHOUSE_HOST is honored only when no connection flag is given, so pass
# --host and --port together. The user must be able to set `log_comment` (used for cutover attribution in
# query_log): a `readonly = 1` profile rejects it outright ("Cannot modify 'log_comment' setting in readonly mode"),
# so a read-only assessor needs `readonly = 2` and the migration user needs a non-readonly profile.
#
# Options:
#   --database NAME           analytics database (e.g. opik). Required.
#   --port N                  ClickHouse NATIVE port, when it is not the default 9000 — e.g. reaching a cluster through
#                             a port-forward or bastion on a local port. Required because clickhouse-client honors
#                             CLICKHOUSE_HOST / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment but does
#                             NOT honor CLICKHOUSE_PORT, so the port cannot be passed via env.
#   --host HOST               ClickHouse host. Pass it together with --port: clickhouse-client honors CLICKHOUSE_HOST
#                             ONLY when no connection flag is given, so supplying --port alone silently reverts the host
#                             to localhost. User/password still come from CLICKHOUSE_USER / CLICKHOUSE_PASSWORD (keeping
#                             the password out of argv).
#   --unwrap-only             reverse ONLY the Distributed wrap (no promote, no reverse-replay). Requires
#                             --confirm-maintenance. Mutually exclusive with --stage and --reverse-replay-only.
#   --confirm-maintenance     REQUIRED with --unwrap-only. The un-wrap is gapless per node (atomic rotate), but renaming
#                             the live `traces` has a brief cross-node ON CLUSTER propagation skew during which a query
#                             routed at a lagging replica's wrapper can fail with UNKNOWN_TABLE. That hits READS, not
#                             only writes, so raising the async-insert buffer does NOT discharge this flag: it asserts
#                             traffic is quiesced or a maintenance window is in effect. Same gate, and the same
#                             read exposure, as the --wrap-only in exchange_and_wrap.sh that this reverses.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/db-app-analytics"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
STAGE=""
CUTOVER_START=""
CONFIRM_RETENTION_PAUSED=0
ACCEPT_WRITE_LOSS=0
REVERSE_REPLAY_ONLY=0
UNWRAP_ONLY=0
CONFIRM_MAINTENANCE=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --stage) STAGE="${2:?"$1 requires a value"}"; shift 2 ;;
        --cutover-start) CUTOVER_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-retention-paused) CONFIRM_RETENTION_PAUSED=1; shift ;;
        --accept-post-cutover-write-loss) ACCEPT_WRITE_LOSS=1; shift ;;
        --reverse-replay-only) REVERSE_REPLAY_ONLY=1; shift ;;
        --unwrap-only) UNWRAP_ONLY=1; shift ;;
        --confirm-maintenance) CONFIRM_MAINTENANCE=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --cutover-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ -z "$CUTOVER_START" || "$CUTOVER_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --cutover-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
# Exactly one mode: --stage A|B|C, --reverse-replay-only, or --unwrap-only.
if (( REVERSE_REPLAY_ONLY + UNWRAP_ONLY > 1 )); then
    echo "ERROR: --reverse-replay-only and --unwrap-only are mutually exclusive." >&2; exit 2
fi
if [[ "$REVERSE_REPLAY_ONLY" == "1" || "$UNWRAP_ONLY" == "1" ]]; then
    [[ -z "$STAGE" ]] || { echo "ERROR: --stage cannot be combined with --reverse-replay-only / --unwrap-only." >&2; exit 2; }
else
    case "$STAGE" in
        A|B|C) ;;
        *) echo "ERROR: --stage must be A, B or C (or pass --reverse-replay-only / --unwrap-only)" >&2; exit 2 ;;
    esac
fi
# Reject the promote/replay flags under --unwrap-only rather than ignoring them. Each asserts a precondition for
# something the un-wrap does not do, so accepting one would quietly confirm a wrong mental model of the operation —
# most damagingly that post-cutover deletes get replayed (they need no replay: nothing is promoted).
if [[ "$UNWRAP_ONLY" == "1" ]]; then
    if [[ -n "$CUTOVER_START" || "$ACCEPT_WRITE_LOSS" == "1" || "$CONFIRM_RETENTION_PAUSED" == "1" ]]; then
        echo "ERROR: --unwrap-only takes none of --cutover-start / --accept-post-cutover-write-loss /" >&2
        echo "       --confirm-retention-paused. It promotes nothing and replays nothing: the successor stays live, so no" >&2
        echo "       write is abandoned and no bridged delete needs re-applying. If you meant to restore the ORIGINAL" >&2
        echo "       table, that is --stage C (and it needs all three)." >&2
        exit 2
    fi
    if [[ "$CONFIRM_MAINTENANCE" != "1" ]]; then
        echo "ERROR: --unwrap-only requires --confirm-maintenance. It renames the live 'traces': gapless per node, but with" >&2
        echo "       a brief cross-node ON CLUSTER skew during which a lagging replica still resolves the wrapper's" >&2
        echo "       'traces_local' target, which the already-renamed replicas no longer have — so a query routed there can" >&2
        echo "       fail with UNKNOWN_TABLE. That hits READS as well as writes, so the async-insert buffer alone does not" >&2
        echo "       cover it: quiesce traffic or take a maintenance window (the mirror of the window exchange_and_wrap.sh" >&2
        echo "       gates for the --wrap-only this reverses), then re-run with the flag." >&2
        exit 2
    fi
fi
# The reverse-replay runs for stages B/C and for --reverse-replay-only, and — like the forward replay — only re-applies
# bridged deletes. Retention deletes (deleteForRetention*) bypass the bridge, so a retention sweep during the rollback
# window would restore a legitimately deleted row from the backup and resurrect it. Retention is a backend setting the
# script can't read; require the operator to assert it is paused. Stage A does no reverse-replay, so it is exempt.
if [[ ( "$STAGE" == "B" || "$STAGE" == "C" || "$REVERSE_REPLAY_ONLY" == "1" ) && "$CONFIRM_RETENTION_PAUSED" != "1" ]]; then
    echo "ERROR: this rollback runs the reverse-replay and requires --confirm-retention-paused. It re-applies only" >&2
    echo "       bridged deletes; a retention sweep in the rollback window would resurrect a deleted row from the backup." >&2
    echo "       Pause retention (RETENTION_ENABLED=false on every backend), then re-run with the flag." >&2
    exit 2
fi
# Stages B/C promote the frozen pre-cutover backup, so writes the successor accepted after cutover_start stop being live
# (they are preserved in the parked traces_post_rollback_backup until finalize.sh, recoverable during the soak). This is
# unavoidable when promoting a point-in-time backup — require the operator to acknowledge it, not a precondition they fix.
if [[ ( "$STAGE" == "B" || "$STAGE" == "C" ) && "$ACCEPT_WRITE_LOSS" != "1" ]]; then
    echo "ERROR: rollback --stage $STAGE requires --accept-post-cutover-write-loss. Promoting the frozen backup makes" >&2
    echo "       traces written to the successor after cutover_start non-live. They are NOT destroyed — the successor is" >&2
    echo "       parked as traces_post_rollback_backup until finalize.sh, so recover them during the soak — but the live" >&2
    echo "       table will no longer serve them. Re-run with the flag once you accept this." >&2
    exit 2
fi

ch() {
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --log_comment 'traces_local_v2_rollback' --query "$1"
}

# Single scalar (or empty string if the object does not exist). Used by the topology guards below.
traces_engine() {
    ch "SELECT engine FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}
traces_endtime_type() {
    ch "SELECT type FROM system.columns WHERE database = '$DATABASE' AND table = '$1' AND name = 'end_time'"
}

# The migration walks traces through three shapes; a stage is only valid in one of them:
#   pre-EXCHANGE       -> traces is a *MergeTree with Nullable end_time (the original schema)      -> stage A
#   post-EXCHANGE      -> traces is a *MergeTree with non-Nullable end_time (the successor schema) -> stage B
#   post-wrap          -> traces is a Distributed table                                           -> stage C
# Asserting the shape makes a wrong-stage run (which is where a TRUNCATE/DROP would be catastrophic) abort with no change.
assert_topology() {
    local engine end_time
    engine="$(traces_engine traces)"
    end_time="$(traces_endtime_type traces)"
    [[ -n "$engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }

    case "$STAGE" in
        A)
            [[ "$engine" != "Distributed" && "$end_time" == Nullable* ]] || {
                echo "ERROR: stage A expects the pre-EXCHANGE state (traces = original schema), but traces is engine='$engine' end_time='$end_time'." >&2
                echo "       The EXCHANGE has already run — truncating the shadow now would destroy the parked original. Use stage B or C." >&2
                exit 1
            }
            [[ -n "$(traces_engine traces_local_v2)" ]] || { echo "ERROR: shadow table 'traces_local_v2' not found; nothing to discard." >&2; exit 1; }
            ;;
        B)
            [[ "$engine" != "Distributed" ]] || {
                echo "ERROR: stage B expects the post-EXCHANGE, pre-wrap state, but traces is Distributed (the wrap ran). Use stage C." >&2
                exit 1
            }
            [[ "$end_time" != Nullable* ]] || {
                echo "ERROR: stage B expects traces to hold the successor schema, but end_time is Nullable (the EXCHANGE has not run). Nothing to roll back; use stage A to discard the shadow." >&2
                exit 1
            }
            if [[ -z "$(traces_engine traces_pre_cutover_backup)" ]]; then
                if [[ -n "$(traces_engine traces_local_v2)" ]]; then
                    # State X: the forward EXCHANGE succeeded but its post-swap RENAME did not, so the parked original is
                    # still under traces_local_v2. Finish that RENAME (the same remediation exchange_and_wrap.sh prints),
                    # then stage B proceeds normally. Not auto-completed here: rollback does exactly one thing per run.
                    echo "ERROR: 'traces_pre_cutover_backup' not found but 'traces_local_v2' still exists — the forward" >&2
                    echo "       EXCHANGE's post-swap RENAME did not complete, so the parked original is still under" >&2
                    echo "       'traces_local_v2'. Finish that RENAME, then re-run stage B:" >&2
                    echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
                else
                    echo "ERROR: 'traces_pre_cutover_backup' (parked original) not found; cannot swap back." >&2
                fi
                exit 1
            fi
            ;;
        C)
            [[ "$engine" == "Distributed" ]] || {
                echo "ERROR: stage C expects the post-wrap state (traces = Distributed), but traces is engine='$engine'. The wrap was not applied — use stage B." >&2
                exit 1
            }
            [[ -n "$(traces_engine traces_local)" ]] || { echo "ERROR: 'traces_local' (successor data) not found; topology is not a clean post-wrap state." >&2; exit 1; }
            [[ -n "$(traces_engine traces_pre_cutover_backup)" ]] || { echo "ERROR: 'traces_pre_cutover_backup' (parked original) not found; topology is not a clean post-wrap state." >&2; exit 1; }
            ;;
    esac
}

# Run one rollback .sql file wholesale, substituting the placeholders. Each file is exactly one stage's statements.
run_file() {
    local file="$SQL_DIR/$1" sql
    [[ -f "$file" ]] || { echo "ERROR: cannot find $file" >&2; exit 2; }
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --multiquery --query "$sql"
}

# Un-wrap mode: reverse the Distributed wrap and stop, leaving the partitioned successor live (see
# 000004_rollback_unwrap.sql). Guarded on the post-wrap shape. Deliberately does NOT require traces_pre_cutover_backup —
# that is what makes it the only wrap recovery still available after finalize.sh has dropped the backup.
if [[ "$UNWRAP_ONLY" == "1" ]]; then
    unwrap_engine="$(traces_engine traces)"
    [[ -n "$unwrap_engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }
    [[ "$unwrap_engine" == "Distributed" ]] || {
        echo "ERROR: --unwrap-only expects the post-wrap state (traces = Distributed), but traces is engine='$unwrap_engine'." >&2
        echo "       The wrap is not applied, so there is nothing to un-wrap. To roll the CUTOVER back, use --stage B (or" >&2
        echo "       --stage A if the EXCHANGE never ran)." >&2
        exit 1
    }
    [[ -n "$(traces_engine traces_local)" ]] || {
        echo "ERROR: --unwrap-only: 'traces_local' (the successor shard the wrapper fronts) not found. The topology is not a" >&2
        echo "       clean post-wrap state, and promoting a missing table would leave 'traces' absent. Resolve by hand." >&2
        exit 1
    }
    # 'traces_local' must be the SUCCESSOR, not the original parked under that name by some earlier manual step. Promoting
    # the original here would silently revert the schema with none of the flag reverts or sentinel repair stage B/C carry.
    # Require the column to EXIST and be non-Nullable, in that order: traces_endtime_type returns an empty string for a
    # missing column, and "" is not Nullable*, so a bare non-Nullable test would wave through a table that has no
    # end_time at all — i.e. not the successor either.
    unwrap_local_end_time="$(traces_endtime_type traces_local)"
    [[ -n "$unwrap_local_end_time" ]] || {
        echo "ERROR: --unwrap-only: 'traces_local' has no 'end_time' column, so it is not the successor this wrapper is" >&2
        echo "       meant to front. Promoting it would make an unrelated table the live 'traces'. Refusing — resolve the" >&2
        echo "       topology by hand." >&2
        exit 1
    }
    [[ "$unwrap_local_end_time" != Nullable* ]] || {
        echo "ERROR: --unwrap-only: 'traces_local' has Nullable end_time, i.e. it holds the ORIGINAL schema, not the" >&2
        echo "       successor. Promoting it would revert the schema without the flag reverts and sentinel repair that a" >&2
        echo "       real rollback performs. Refusing — resolve the topology by hand." >&2
        exit 1
    }
    # RENAME cannot overwrite an existing name, so a leftover 'traces_dist_old' (from an earlier stage C or un-wrap whose
    # RENAME landed but whose DROP did not, followed by a re-wrap) would fail the rotate below with a bare
    # "table already exists". Report it here instead: it is data-less, so the fix is simply to drop it.
    [[ -z "$(traces_engine traces_dist_old)" ]] || {
        echo "ERROR: --unwrap-only: '$DATABASE.traces_dist_old' already exists, so the atomic rotate below cannot claim that" >&2
        echo "       name. It is the data-less ex-wrapper left by an earlier stage C / un-wrap whose DROP did not complete." >&2
        echo "       Drop it and re-run:" >&2
        echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"DROP TABLE IF EXISTS $DATABASE.traces_dist_old ON CLUSTER '{cluster}' SYNC\"" >&2
        exit 1
    }
    echo "NOTE: reversing the Distributed wrap only. The partitioned successor stays live, so nothing is promoted and no" >&2
    echo "      deletes are replayed; this lands in the post-EXCHANGE, pre-wrap state." >&2
    run_file 000004_rollback_unwrap.sql
    echo "Un-wrap done: the Distributed wrapper is gone and 'traces' is the partitioned successor again."
    echo
    echo "NEXT, in this order:"
    echo "  1. Set databaseAnalyticsDataModel.tracesDistributedWrapEnabled=false and roll-restart every backend instance."
    echo "     Do it in THIS order (DDL first, flag second), which is the inverse of the forward wrap and keeps the failure"
    echo "     on the same side: until the restart completes, trace DELETES target the now-absent 'traces_local' and fail"
    echo "     with Code 60 UNKNOWN_TABLE. Reverting the flag first instead would point them at a 'traces' that is still"
    echo "     Distributed, which rejects mutations (Code 36) AND exposes the cross-node skew unbuffered. Either window is"
    echo "     delete-path-only — reads and inserts never consult the flag — so keep it short and fail loud."
    echo "  2. Leave databaseAnalyticsDataModel.traceColumnsNonNullable=true: the live table keeps the successor's sentinel"
    echo "     schema. Leave tracesWeeklyPartitionPruningEnabled as it is — it asserts the live table is the partitioned"
    echo "     successor, which is still true. No sentinel/duration repair is needed (that is a stage B/C concern)."
    echo "  3. The partition metrics relabel back: the opik.clickhouse.partition.* parts gauges move from"
    echo "     table=\"traces_local\" to table=\"traces\", so restore any dashboards/alerts adjusted at wrap time. AND if the"
    echo "     wrap-time option to point PARTITION_METRICS_LWD_TABLES at 'traces_local' was taken, revert it to 'traces'"
    echo "     now: that table is gone, so the LWD scan would fail (Code 60) and opik.clickhouse.partition.lwd_rows would"
    echo "     go silently empty while the other gauges come back. Default is 'traces,spans' — untouched installs are fine."
    echo
    echo "To re-apply the wrap later, once the cause is understood:"
    if [[ -n "$(traces_engine traces_pre_cutover_backup)" ]]; then
        echo "  ./exchange_and_wrap.sh --database $DATABASE --wrap-only --confirm-maintenance --confirm-daos-retargeted"
        echo "  (flip tracesDistributedWrapEnabled back to true first, per the runbook's toggle/wrap ordering note)."
    else
        # Deliberately not printed as a runnable command: --wrap-only refuses without the parked original, and this is
        # exactly the post-finalize estate un-wrap exists to serve — so telling the operator to run it would send them
        # into a guaranteed refusal.
        echo "  NOT AVAILABLE as-is: 'traces_pre_cutover_backup' is gone (finalize.sh ran), and exchange_and_wrap.sh"
        echo "  --wrap-only refuses without it — by design, since re-wrapping an estate that has no route back to the"
        echo "  pre-cutover table should be a deliberate, separately reviewed decision, not a default. The un-wrap above"
        echo "  is unaffected and remains repeatable. To re-wrap here, get that guard lifted first (see the runbook's"
        echo "  \"Un-wrap\" section); do NOT work around it by hand."
    fi
    exit 0
fi

# Recovery mode: re-apply only the reverse-replay against the current live `traces`, for a stage B/C run whose promote
# succeeded but whose reverse-replay was interrupted. It is idempotent, so re-running just re-masks any deletes it missed.
# CRITICAL: 000004_rollback_reverse_replay.sql carries NO resurrection guard — correct ONLY against the restored ORIGINAL
# (where a bridged id is present as its pre-cutover version). Against the live SUCCESSOR (post-EXCHANGE, pre-rollback,
# also a non-Distributed MergeTree) it would unconditionally mask ids deleted-then-recreated after cutover_start — silent
# deletion of live rows. So assert the post-promote shape, not just "non-Distributed": `traces` is the ORIGINAL schema
# (Nullable end_time) AND the successor is parked as traces_post_rollback_backup (only a completed promote creates it).
if [[ "$REVERSE_REPLAY_ONLY" == "1" ]]; then
    [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for --reverse-replay-only" >&2; exit 2; }
    reverse_replay_engine="$(traces_engine traces)"
    [[ -n "$reverse_replay_engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }
    [[ "$reverse_replay_engine" != "Distributed" ]] || {
        echo "ERROR: 'traces' is Distributed, so the wrap is still applied — the reverse-replay runs on the restored" >&2
        echo "       original MergeTree. Roll the wrap back first with --stage C." >&2
        exit 1
    }
    if [[ "$(traces_endtime_type traces)" != Nullable* || -z "$(traces_engine traces_post_rollback_backup)" ]]; then
        echo "ERROR: --reverse-replay-only requires the post-rollback state — 'traces' = the restored ORIGINAL (Nullable" >&2
        echo "       end_time) with the successor parked as 'traces_post_rollback_backup'. The reverse-replay carries no" >&2
        echo "       resurrection guard, so run against the live successor (EXCHANGE ran but no rollback did) it would" >&2
        echo "       mask deleted-then-recreated rows — silent data loss. Roll back with --stage B (or --stage C after the" >&2
        echo "       wrap) first; only use --reverse-replay-only if that stage's promote succeeded but its replay did not." >&2
        exit 1
    fi
    echo "NOTE: re-applying the reverse deletion replay only (no table swap) for deletes since cutover_start" >&2
    echo "      ($CUTOVER_START). Idempotent; use this after a stage B/C run whose reverse-replay was interrupted." >&2
    run_file 000004_rollback_reverse_replay.sql
    echo "Reverse-replay-only done: bridged deletes since cutover_start re-applied to the live 'traces'."
    exit 0
fi

assert_topology

if [[ "$STAGE" == "B" || "$STAGE" == "C" ]]; then
    echo "NOTE: promoting the frozen backup now. Traces the successor accepted after cutover_start ($CUTOVER_START) will" >&2
    echo "      stop being live; recover them from the parked traces_post_rollback_backup (kept until finalize.sh) if needed." >&2
    echo "NOTE: the promote is a single ON CLUSTER RENAME of the live 'traces' — synchronous across the shard's replicas, but" >&2
    echo "      with a brief sub-second cross-replica read skew as it propagates (a read on a lagging replica sees the" >&2
    echo "      pre-rollback 'traces'). On a multi-replica cluster run this in a maintenance moment / with reads quiesced, as for the wrap." >&2
fi

case "$STAGE" in
    A)
        run_file 000004_rollback_stage_a_discard_shadow.sql
        echo "Stage A done: shadow 'traces_local_v2' discarded (now empty); live 'traces' was untouched."
        echo "Nothing to soak or finalize — re-run the backfill to retry the cutover, or stop here."
        ;;
    B)
        [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for stage B" >&2; exit 2; }
        run_file 000004_rollback_stage_b_exchange_back.sql
        run_file 000004_rollback_reverse_replay.sql
        echo "Stage B done: tables swapped back and deletes since cutover_start re-applied."
        ;;
    C)
        [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for stage C" >&2; exit 2; }
        run_file 000004_rollback_stage_c_promote_original.sql
        run_file 000004_rollback_reverse_replay.sql
        echo "Stage C done: wrapper dropped, original promoted, deletes since cutover_start re-applied."
        ;;
esac

if [[ "$STAGE" == "B" || "$STAGE" == "C" ]]; then
    echo "Now in the canonical state: traces = original data (live), traces_post_rollback_backup = successor data (parked)."
    echo "Verify with the POST-ROLLBACK table pair — the verify.sh defaults no longer apply (traces_local_v2 is gone), and"
    echo "the CURRENT week legitimately mismatches by the post-cutover writes this rollback discarded, so bound it:"
    echo "  ./verify.sh --database $DATABASE --old-table traces --new-table traces_post_rollback_backup --to-week <last sealed week>"
    echo "Then run finalize.sh once healthy — it recycles the backup into an empty traces_local_v2 (restoring the"
    echo "pre-cutover shadow), the one irreversible step."
    echo
    echo "NEXT, on the restored original (see README 'Rolling back the traceColumnsNonNullable flip'):"
    echo "  1. Set databaseAnalyticsDataModel.traceColumnsNonNullable=false and roll-restart every backend instance."
    echo "     Until that lands, absent end_time/ttft read back as the epoch/NaN sentinel instead of NULL."
    echo "  2. Repair the epoch/NaN sentinels written into the still-Nullable original while the flag was true. The"
    echo "     original stores an absent value as NULL, so those rows now read as 'ended at 1970' / 'ttft 0-ish', and"
    echo "     its MATERIALIZED duration expression — which guards only 'end_time IS NOT NULL', not the sentinel —"
    echo "     computed a large NEGATIVE duration that the promote made live again. Count, then restore NULL (a"
    echo "     MATERIALIZE COLUMN alone would NOT fix it: it re-evaluates the same expression on the same sentinel):"
    echo "       clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"SELECT countIf(end_time = toDateTime64('1970-01-01 00:00:00', 9)) AS sentinel_end_time, countIf(isNaN(ttft)) AS sentinel_ttft, countIf(duration < 0) AS negative_duration_total, countIf(duration < 0 AND end_time = toDateTime64('1970-01-01 00:00:00', 9)) AS negative_from_sentinel FROM $DATABASE.traces\""
    echo "       clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"ALTER TABLE $DATABASE.traces ON CLUSTER '{cluster}' UPDATE end_time = NULL WHERE end_time = toDateTime64('1970-01-01 00:00:00', 9) SETTINGS mutations_sync = 2\""
    echo "       clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"ALTER TABLE $DATABASE.traces ON CLUSTER '{cluster}' UPDATE ttft = NULL WHERE isNaN(ttft) SETTINGS mutations_sync = 2\""
    echo "     The mutation rewrites the affected parts and recomputes 'duration' from the restored NULL. Success is"
    echo "     'sentinel_end_time' and 'sentinel_ttft' reaching 0 — NOT 'negative_duration_total': rows whose end_time"
    echo "     genuinely precedes start_time are a pre-existing source artifact this repair does not address, and they"
    echo "     stay negative. Do step 1 FIRST, or in-flight writes keep minting more sentinels."
    echo "     NOTE: these two statements need ALTER UPDATE(end_time) / ALTER UPDATE(ttft) on 'traces'. A user scoped to"
    echo "     the rollback grant set has only ALTER UPDATE(_row_exists) — all the reverse replay needs — and will get"
    echo "     ACCESS_DENIED here, so run the repair as a more privileged user or add those two column grants."
fi
