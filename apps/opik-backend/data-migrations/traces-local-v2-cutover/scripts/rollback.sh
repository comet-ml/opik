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
# Or --sentinel-repair-only: after a stage B/C promote, restore NULL on the rows written into the still-Nullable
# original while traceColumnsNonNullable was true (they read back as an epoch/NaN sentinel, and its MATERIALIZED
# duration computed a large negative from them). Kept separate from the stages rather than appended to them because the
# flag revert must land on every backend FIRST — otherwise in-flight writes keep minting sentinels behind the repair —
# and rolling out config is outside these DB-facing scripts. Skips the mutation when there is nothing to repair, so it
# is safe to run speculatively. See 000004_rollback_sentinel_repair.sql.
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
#   --sentinel-repair-only    repair ONLY the epoch/NaN sentinels on the restored original (no promote, no replay, no
#                             rename). Requires --confirm-flag-reverted. Mutually exclusive with --stage,
#                             --reverse-replay-only and --unwrap-only.
#   --confirm-flag-reverted   REQUIRED with --sentinel-repair-only, and accepted by no other mode. Asserts
#                             databaseAnalyticsDataModel.traceColumnsNonNullable=false is live on EVERY backend
#                             instance. The scripts cannot read backend config, and a repair run while any instance
#                             still has it true is not merely incomplete: that instance keeps writing fresh sentinels
#                             behind the mutation, so the counts can clear and then regress.
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
REPLAY_CHECK_FAILED=0    # set by verify_replay_postcondition; decides the exit code without cutting the guidance short
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
STAGE=""
CUTOVER_START=""
CONFIRM_RETENTION_PAUSED=0
ACCEPT_WRITE_LOSS=0
REVERSE_REPLAY_ONLY=0
UNWRAP_ONLY=0
SENTINEL_REPAIR_ONLY=0
CONFIRM_MAINTENANCE=0
CONFIRM_FLAG_REVERTED=0
SENTINEL_CHECK_FAILED=0  # set by the sentinel postcondition; decides the exit code without cutting the guidance short

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --stage) STAGE="${2:?"$1 requires a value"}"; shift 2 ;;
        --cutover-start) CUTOVER_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-retention-paused) CONFIRM_RETENTION_PAUSED=1; shift ;;
        --accept-post-cutover-write-loss) ACCEPT_WRITE_LOSS=1; shift ;;
        --reverse-replay-only) REVERSE_REPLAY_ONLY=1; shift ;;
        --unwrap-only) UNWRAP_ONLY=1; shift ;;
        --sentinel-repair-only) SENTINEL_REPAIR_ONLY=1; shift ;;
        --confirm-maintenance) CONFIRM_MAINTENANCE=1; shift ;;
        --confirm-flag-reverted) CONFIRM_FLAG_REVERTED=1; shift ;;
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
# Exactly one mode: --stage A|B|C, --reverse-replay-only, --unwrap-only, or --sentinel-repair-only.
if (( REVERSE_REPLAY_ONLY + UNWRAP_ONLY + SENTINEL_REPAIR_ONLY > 1 )); then
    echo "ERROR: --reverse-replay-only, --unwrap-only and --sentinel-repair-only are mutually exclusive." >&2; exit 2
fi
if (( REVERSE_REPLAY_ONLY + UNWRAP_ONLY + SENTINEL_REPAIR_ONLY == 1 )); then
    [[ -z "$STAGE" ]] || { echo "ERROR: --stage cannot be combined with --reverse-replay-only / --unwrap-only / --sentinel-repair-only." >&2; exit 2; }
else
    case "$STAGE" in
        A|B|C) ;;
        *) echo "ERROR: --stage must be A, B or C (or pass --reverse-replay-only / --unwrap-only / --sentinel-repair-only)" >&2; exit 2 ;;
    esac
fi
# --confirm-flag-reverted asserts a precondition only the sentinel repair has. Reject it elsewhere rather than ignoring
# it: silently accepting it would let an operator believe a stage B/C run had taken the flag state into account, when
# stages B/C in fact run BEFORE the revert and print it as their own next step.
if [[ "$CONFIRM_FLAG_REVERTED" == "1" && "$SENTINEL_REPAIR_ONLY" != "1" ]]; then
    echo "ERROR: --confirm-flag-reverted belongs to --sentinel-repair-only and to no other mode. Stages B/C run BEFORE" >&2
    echo "       traceColumnsNonNullable is reverted — reverting it is the next step they print — so asserting it here" >&2
    echo "       would assert the opposite of the sequence. Run the stage, land the revert, then re-run with" >&2
    echo "       --sentinel-repair-only --confirm-flag-reverted." >&2
    exit 2
fi
# Reject the promote/replay/maintenance flags under --sentinel-repair-only rather than ignoring them, for the same reason
# --unwrap-only does: each asserts a precondition for something the repair does not do. It promotes nothing, replays
# nothing, and renames nothing — it is one mutation on the already-live table, so there is no cross-node rename skew for
# --confirm-maintenance to gate and no window for retention to interfere with.
if [[ "$SENTINEL_REPAIR_ONLY" == "1" ]]; then
    if [[ -n "$CUTOVER_START" || "$ACCEPT_WRITE_LOSS" == "1" || "$CONFIRM_RETENTION_PAUSED" == "1" || "$CONFIRM_MAINTENANCE" == "1" ]]; then
        echo "ERROR: --sentinel-repair-only takes none of --cutover-start / --accept-post-cutover-write-loss /" >&2
        echo "       --confirm-retention-paused / --confirm-maintenance. It repairs column values on the table that is" >&2
        echo "       ALREADY live: nothing is promoted, no delete is replayed, and no table is renamed, so there is" >&2
        echo "       neither a rollback window to bound nor a rename skew to quiesce reads for." >&2
        exit 2
    fi
    if [[ "$CONFIRM_FLAG_REVERTED" != "1" ]]; then
        echo "ERROR: --sentinel-repair-only requires --confirm-flag-reverted. Set" >&2
        echo "       databaseAnalyticsDataModel.traceColumnsNonNullable=false and roll-restart every backend instance" >&2
        echo "       first. Repairing while any instance still has it true does not just leave rows behind: that" >&2
        echo "       instance keeps writing fresh sentinels, so the counts can clear and then regress, and the run" >&2
        echo "       reports a success that is already stale. Re-run with the flag once the revert is live everywhere." >&2
        exit 2
    fi
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

# Stage C and --unwrap-only both rotate `traces` out to 'traces_dist_old', and RENAME cannot overwrite an existing name. A
# leftover fails the rotate cleanly (it is atomic per host) but as a bare "table already exists", so refuse here with the
# remedy instead. Shared so the two cannot drift.
#
# Only claim it IS the data-less ex-wrapper, and only suggest dropping it, once the engine says so. Presence under this name
# is convention, not proof: the suggested DROP is ON CLUSTER, so if anything else held the name, following that advice
# would destroy it on every replica. A Distributed table is a routing definition and holds no rows, which is what makes the
# drop safe in the expected case — and the only thing that does.
assert_traces_dist_old_free() {
    local context="$1" engine definition
    engine="$(traces_engine traces_dist_old)"
    [[ -n "$engine" ]] || return 0
    echo "ERROR: $context: '$DATABASE.traces_dist_old' already exists, so the atomic rotate cannot claim that name." >&2
    if [[ "$engine" == "Distributed" ]]; then
        # TSVRaw: the default TabSeparated escapes the quotes in engine_full, so the printed DDL would not match what the
        # operator sees in ClickHouse.
        definition="$(ch "SELECT engine_full FROM system.tables WHERE database = '$DATABASE' AND name = 'traces_dist_old' FORMAT TSVRaw" 2>/dev/null || true)"
        echo "       It is the data-less ex-wrapper left by an earlier stage C / un-wrap whose DROP did not complete." >&2
        [[ -z "$definition" ]] || echo "       Definition: $definition" >&2
        echo "       Dropping it loses no rows — a Distributed table only routes. Confirm the target above, then:" >&2
        echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"DROP TABLE IF EXISTS $DATABASE.traces_dist_old ON CLUSTER '{cluster}' SYNC\"" >&2
    else
        echo "       It is engine='$engine', NOT the data-less Distributed ex-wrapper this name is reserved for, so it may" >&2
        echo "       hold rows. Deliberately NOT suggesting a DROP here: that statement is ON CLUSTER and would apply on" >&2
        echo "       every replica. Identify what owns this name and resolve it by hand." >&2
    fi
    exit 1
}

# --reverse-replay-only and --sentinel-repair-only both act on a COMPLETED promote and are unsafe anywhere else, so they
# share this assertion. Three signals, each ruling out a different wrong target:
#   - `traces` has Nullable end_time — it is the restored ORIGINAL, not the live successor.
#   - `traces_post_rollback_backup` exists AND holds the successor schema (non-Nullable end_time) — a promote really
#     parked the successor there. Presence alone proves nothing: the name is convention, and a table under it carrying
#     the ORIGINAL schema means something else owns it, which is exactly the pre-cutover estate these modes must refuse.
#   - `traces_pre_cutover_backup` is GONE — the promote's rename consumes it. Still present alongside the other two is a
#     contradiction (a half-done rename, or a retry that re-created it), and neither mode should guess which.
# $1 is the mode; the rest are consequence lines, so each caller keeps its own explanation of what getting it wrong costs.
assert_post_promote_state() {
    local mode="$1" traces_end_time backup_end_time pre_cutover line
    shift
    traces_end_time="$(traces_endtime_type traces)"
    backup_end_time="$(traces_endtime_type traces_post_rollback_backup)"
    pre_cutover="$(traces_engine traces_pre_cutover_backup)"
    if [[ "$traces_end_time" == Nullable* && -n "$backup_end_time" && "$backup_end_time" != Nullable* && -z "$pre_cutover" ]]; then
        return 0
    fi
    echo "ERROR: $mode requires the post-rollback state — 'traces' = the restored ORIGINAL (Nullable end_time), the" >&2
    echo "       successor parked as 'traces_post_rollback_backup' with the successor schema (non-Nullable end_time), and" >&2
    echo "       no 'traces_pre_cutover_backup' left, since a promote's rename consumes it." >&2
    echo "       Observed: traces end_time='$traces_end_time', traces_post_rollback_backup end_time='${backup_end_time:-<absent>}'," >&2
    echo "                 traces_pre_cutover_backup engine='${pre_cutover:-<absent>}'." >&2
    for line in "$@"; do
        echo "       $line" >&2
    done
    echo "       Roll back with --stage B (or --stage C after the wrap) first." >&2
    exit 1
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
            assert_traces_dist_old_free "stage C"
            ;;
    esac
}

# Same sourcing contract as run_file (versioned .sql, same two placeholders), but captures the returned value so the
# driver asserts on it instead of leaving a number on the operator's screen to interpret.
verify_replay_postcondition() {
    local file="$SQL_DIR/000004_rollback_verify_replay.sql" sql resurrected
    [[ -f "$file" ]] || { echo "ERROR: cannot find $file" >&2; exit 2; }
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    resurrected="$(clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --query "$sql")"
    if [[ "$resurrected" == "0" ]]; then
        echo "Reverse-replay postcondition OK: no id bridged since cutover_start is live on the restored 'traces'."
        return 0
    fi
    # Never aborts: the promote already succeeded either way, and the operator still needs the flag-revert and repair
    # guidance that prints after this. The failure travels in the exit code instead. A failed client (dead port-forward,
    # auth, network) returns empty or non-numeric output, which is "not verified" rather than "N resurrected" — both fail
    # the check, but only one is a connection problem, so say which.
    if ! [[ "$resurrected" =~ ^[0-9]+$ ]]; then
        echo "WARNING: reverse-replay postcondition COULD NOT BE EVALUATED — the query returned no usable count." >&2
        echo "         Treat the rollback as unverified, not as clean. Fix connectivity and re-run the check below." >&2
    else
        echo "WARNING: reverse-replay postcondition FAILED — $resurrected id(s) deleted after cutover_start are live again" >&2
        echo "         on 'traces'. The rollback is NOT complete: those rows were deleted by users and are being served." >&2
    fi
    echo "         Re-run the replay (idempotent), then this check repeats:" >&2
    echo "           ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --reverse-replay-only --cutover-start '$CUTOVER_START' --confirm-retention-paused" >&2
    return 1
}

# Same sourcing contract as run_file (versioned .sql, one placeholder here — it takes no cutover window). Returns the
# three counts as one tab-separated line so the driver gates on them, instead of leaving numbers on a screen to read.
sentinel_counts() {
    local file="$SQL_DIR/000004_rollback_verify_sentinels.sql" sql
    [[ -f "$file" ]] || { echo "ERROR: cannot find $file" >&2; exit 2; }
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --query "$sql"
}

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
    assert_traces_dist_old_free "--unwrap-only"
    echo "NOTE: reversing the Distributed wrap only. The partitioned successor stays live, so nothing is promoted and no" >&2
    echo "      deletes are replayed; this lands in the post-EXCHANGE, pre-wrap state." >&2
    run_file 000004_rollback_unwrap.sql
    echo "Un-wrap done: the Distributed wrapper is gone and 'traces' is the partitioned successor again."
    echo
    echo "NEXT, in this order:"
    echo "  1. Set databaseAnalyticsDataModel.tracesDistributedWrapEnabled=false and roll-restart every backend instance."
    echo "     (Config push or rolling restart, whichever this deployment uses — the mechanism is outside these"
    echo "     DB-facing scripts by design; see the runbook, \"The only manual actions are not SQL\".)"
    echo "     Do it in THIS order (DDL first, flag second), which is the inverse of the forward wrap and keeps the failure"
    echo "     on the same side: until the restart completes, trace DELETES target the now-absent 'traces_local' and fail"
    echo "     with Code 60 UNKNOWN_TABLE. Reverting the flag first instead would point them at a 'traces' that is still"
    echo "     Distributed, which rejects mutations (Code 36) AND exposes the cross-node skew unbuffered. Either window is"
    echo "     delete-path-only — reads and inserts never consult the flag — so keep it short and fail loud."
    echo "  2. Leave databaseAnalyticsDataModel.traceColumnsNonNullable=true: the live table keeps the successor's sentinel"
    echo "     schema. Step 1's wrap flag is the only one this stage reverts, and trace-delete partition pruning is not a"
    echo "     flag at all. No sentinel/duration repair is needed either (that is a stage B/C concern)."
    echo "  3. The partition metrics relabel back: the opik.clickhouse.partition.* parts gauges move from"
    echo "     table=\"traces_local\" to table=\"traces\", so restore any dashboards/alerts adjusted at wrap time. AND if the"
    echo "     wrap-time option to point PARTITION_METRICS_LWD_TABLES at 'traces_local' was taken, revert it to 'traces'"
    echo "     now: that table is gone, so the LWD scan would fail (Code 60) and opik.clickhouse.partition.lwd_rows would"
    echo "     go silently empty while the other gauges come back. Default is 'traces,spans' — untouched installs are fine."
    echo
    echo "To re-apply the wrap later, once the cause is understood:"
    if [[ -n "$(traces_engine traces_pre_cutover_backup)" ]]; then
        echo "  ./exchange_and_wrap.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --wrap-only --confirm-maintenance --confirm-daos-retargeted"
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
    assert_post_promote_state "--reverse-replay-only" \
        "The reverse-replay carries no resurrection guard, so aimed at the live successor (EXCHANGE ran but no rollback" \
        "did) it would mask deleted-then-recreated rows — silent data loss. Use this mode only when a stage B/C promote" \
        "succeeded but its replay did not."
    echo "NOTE: re-applying the reverse deletion replay only (no table swap) for deletes since cutover_start" >&2
    echo "      ($CUTOVER_START). Idempotent; use this after a stage B/C run whose reverse-replay was interrupted." >&2
    run_file 000004_rollback_reverse_replay.sql
    echo "Reverse-replay-only done: bridged deletes since cutover_start re-applied to the live 'traces'."
    verify_replay_postcondition || REPLAY_CHECK_FAILED=1
    exit "$REPLAY_CHECK_FAILED"
fi

# Sentinel-repair mode: restore NULL on the rows the flag wrote into the still-Nullable original, and recompute their
# duration as a side effect (see 000004_rollback_sentinel_repair.sql). Shares assert_post_promote_state with
# --reverse-replay-only: the epoch/NaN values are damage only on a restored original, being the schema's own encoding of
# "absent" on the successor and ordinary client data on a table that never went through a cutover.
if [[ "$SENTINEL_REPAIR_ONLY" == "1" ]]; then
    sentinel_engine="$(traces_engine traces)"
    [[ -n "$sentinel_engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }
    [[ "$sentinel_engine" != "Distributed" ]] || {
        echo "ERROR: 'traces' is Distributed, so the wrap is still applied and the repair would target a routing" >&2
        echo "       definition that holds no rows. Roll the wrap back first with --stage C." >&2
        exit 1
    }
    assert_post_promote_state "--sentinel-repair-only" \
        "On the live successor the epoch/NaN values are the schema's own encoding of an absent value, not damage; on an" \
        "original that never went through a cutover an epoch end_time is what a client actually sent. Only a completed" \
        "promote tells those apart. If finalize.sh has already recycled the parked successor, this refusal is by design:" \
        "the repair belongs before finalize, so resolve that ordering deliberately rather than around the guard."
    echo "NOTE: repairing epoch/NaN sentinels on the restored original. Nothing is promoted, replayed or renamed — this" >&2
    echo "      is one mutation on the table that is already live, and it recomputes 'duration' from the restored NULL." >&2

    # Read first: it sizes the work, and a clean table skips the mutation entirely (so the mode is safe to run
    # speculatively). Empty or non-numeric output is a dead client, not a clean table — never let that read as success.
    if ! sentinel_before="$(sentinel_counts)"; then sentinel_before=""; fi
    read -r before_end_time before_ttft before_negative <<< "$sentinel_before"
    if ! [[ "$before_end_time" =~ ^[0-9]+$ && "$before_ttft" =~ ^[0-9]+$ ]]; then
        echo "ERROR: the sentinel counts could not be read, so it is unknown whether there is anything to repair." >&2
        echo "       Treat this as unverified, not as clean. Fix connectivity and re-run — no mutation was issued." >&2
        exit 1
    fi
    echo "Sentinels before repair: end_time=$before_end_time ttft=$before_ttft (of those, serving a negative duration: $before_negative)"
    if (( before_end_time == 0 && before_ttft == 0 )); then
        echo "Nothing to repair: no row on any replica carries an epoch end_time or a NaN ttft. No mutation issued."
        exit 0
    fi

    if ! run_file 000004_rollback_sentinel_repair.sql; then
        echo >&2
        echo "ERROR: the sentinel repair did not complete. If ClickHouse reported ACCESS_DENIED, this is the expected" >&2
        echo "       failure for a user scoped to the rollback grant set: it holds ALTER UPDATE(_row_exists) — all the" >&2
        echo "       reverse replay needs — and this statement needs ALTER UPDATE(end_time) AND ALTER UPDATE(ttft) on" >&2
        echo "       'traces'. It carries both commands in one mutation, so a missing grant on either applies neither;" >&2
        echo "       nothing is half-repaired. Grant the two columns (or run as a more privileged user) and re-run — the" >&2
        echo "       repair is idempotent, and revoke them again afterwards." >&2
        exit 1
    fi

    if ! sentinel_after="$(sentinel_counts)"; then sentinel_after=""; fi
    read -r after_end_time after_ttft after_negative <<< "$sentinel_after"
    if ! [[ "$after_end_time" =~ ^[0-9]+$ && "$after_ttft" =~ ^[0-9]+$ ]]; then
        echo "WARNING: sentinel postcondition COULD NOT BE EVALUATED — the counts came back unreadable. The mutation" >&2
        echo "         reported success, but treat the repair as unverified: re-run this mode to re-read the counts." >&2
        SENTINEL_CHECK_FAILED=1
    elif (( after_end_time == 0 && after_ttft == 0 )); then
        echo "Sentinel postcondition OK: end_time=0 ttft=0 on every replica."
        echo "Repaired $before_end_time end_time and $before_ttft ttft value(s); 'duration' recomputed from the restored NULL."
        echo "A residual count of negative durations elsewhere in the table is EXPECTED and is not this repair's business:"
        echo "rows whose end_time genuinely precedes start_time are a pre-existing source artifact and stay negative."
    else
        echo "WARNING: sentinel postcondition FAILED — end_time=$after_end_time ttft=$after_ttft still present after the" >&2
        echo "         mutation reported success. The likeliest cause is a backend instance still running with" >&2
        echo "         traceColumnsNonNullable=true and minting fresh sentinels behind the repair; --confirm-flag-reverted" >&2
        echo "         asserts that is not so, but cannot check it. Confirm the revert is live on EVERY instance, then" >&2
        echo "         re-run this mode (idempotent)." >&2
        SENTINEL_CHECK_FAILED=1
    fi
    exit "$SENTINEL_CHECK_FAILED"
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
        verify_replay_postcondition || REPLAY_CHECK_FAILED=1
        echo "Stage B done: tables swapped back and deletes since cutover_start re-applied."
        ;;
    C)
        [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for stage C" >&2; exit 2; }
        run_file 000004_rollback_stage_c_promote_original.sql
        run_file 000004_rollback_reverse_replay.sql
        verify_replay_postcondition || REPLAY_CHECK_FAILED=1
        echo "Stage C done: wrapper dropped, original promoted, deletes since cutover_start re-applied."
        ;;
esac

if [[ "$STAGE" == "B" || "$STAGE" == "C" ]]; then
    echo "Now in the canonical state: traces = original data (live), traces_post_rollback_backup = successor data (parked)."
    # The divergence is bounded by the CUTOVER WINDOW, not by the calendar, so print the offset of the last week wholly
    # before cutover_start: unlike a calendar-relative bound it stays correct if the verify runs days later. Same anchor
    # math verify.sh uses on this table, capped at its last populated week (LAST_WEEK, from max(created_at)) because
    # verify.sh rejects a --to-week beyond that; the cap only binds when the window's week is already out of range, where
    # the window is excluded anyway. Advisory, and the rollback has already succeeded — so keep it non-fatal rather than
    # aborting the guidance below.
    bound_week="$(ch "SELECT least(
                          dateDiff('week', toMonday(min(created_at)), toMonday(toDateTime64('$CUTOVER_START', 6))) - 1,
                          dateDiff('week', toMonday(min(created_at)), toMonday(max(created_at)))
                      ) FROM traces" 2>/dev/null || true)"
    echo "Verify with the POST-ROLLBACK table pair — the verify.sh defaults no longer apply (traces_local_v2 is gone), and"
    echo "the cutover window's week legitimately mismatches by the post-cutover writes this rollback discarded, so stop"
    echo "before it. That week is fixed by cutover_start, so the bound does not drift if you verify later:"
    if [[ "$bound_week" =~ ^-?[0-9]+$ ]] && (( bound_week >= 0 )); then
        echo "  ./verify.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --old-table traces --new-table traces_post_rollback_backup --to-week $bound_week"
    elif [[ "$bound_week" =~ ^-?[0-9]+$ ]]; then
        echo "  (none: every row in 'traces' predates no earlier week than the cutover window's own, so there is nothing"
        echo "   to compare below it. Skip the bounded compare.)"
    else
        echo "  ./verify.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --old-table traces --new-table traces_post_rollback_backup --to-week <N>"
        echo "  where N could not be computed just now: it is the whole weeks between toMonday(min(created_at)) on"
        echo "  'traces' and cutover_start's Monday, minus 1."
    fi
    echo "A mismatch inside that bound is NOT automatically corruption. Any write touching a PRE-EXISTING trace after"
    echo "cutover_start diverges it in a sealed week, which no weekly bound excludes: the update endpoint keeps the row's"
    echo "created_at (so the key differs on both sides), while batch ingestion re-stamps it (so the key goes missing from"
    echo "the successor in its original week). Triage with --drill-down, then look each differing id up in the successor"
    echo "WITHOUT a week filter: last_updated_at >= cutover_start means benign. Absent from the successor entirely is the"
    echo "real signal — that one is a copy gap."
    echo
    echo "NEXT, on the restored original (see README 'Rolling back the traceColumnsNonNullable flip'):"
    if [[ "$STAGE" == "C" ]]; then
        echo "  0. Set databaseAnalyticsDataModel.tracesDistributedWrapEnabled=false and roll-restart. Stage C removed the"
        echo "     wrapper and parked 'traces_local', so a stale true sends trace DELETEs at a table that no longer exists"
        echo "     (Code 60 UNKNOWN_TABLE). Nothing else consults the flag, so the window is delete-path-only."
    fi
    echo "  1. Set databaseAnalyticsDataModel.traceColumnsNonNullable=false and roll-restart every backend instance."
    echo "     (Config push or rolling restart, whichever this deployment uses — the mechanism is outside these"
    echo "     DB-facing scripts by design; see the runbook, \"The only manual actions are not SQL\".)"
    echo "     Until that lands, absent end_time/ttft read back as the epoch/NaN sentinel instead of NULL."
    echo "  2. Repair the epoch/NaN sentinels written into the still-Nullable original while the flag was true. The"
    echo "     original stores an absent value as NULL, so those rows now read as 'ended at 1970' / 'ttft NaN', and its"
    echo "     MATERIALIZED duration expression — which guards only 'end_time IS NOT NULL', not the sentinel — computed"
    echo "     a large NEGATIVE duration that the promote made live again. Once step 1 is live on EVERY instance (do it"
    echo "     first, or in-flight writes keep minting sentinels behind the repair):"
    echo "       ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --sentinel-repair-only --confirm-flag-reverted"
    echo "     It counts first and skips the mutation if there is nothing to repair, restores NULL (a MATERIALIZE COLUMN"
    echo "     would NOT: it re-evaluates the same expression on the same sentinel), and asserts the counts reached 0."
    echo "     It needs ALTER UPDATE(end_time) / ALTER UPDATE(ttft) on 'traces', which the rollback grant set does not"
    echo "     carry — the mode explains the ACCESS_DENIED if you hit it."
    echo
    echo "LAST, and only once every step above has landed: finalize.sh recycles traces_post_rollback_backup into an"
    echo "empty traces_local_v2. That is the irreversible step — it destroys the only copy of the post-cutover writes"
    echo "this rollback discarded, and with it the cheap retry. Do not run it until the flag reverts, the sentinel"
    echo "repair and the checks above are done (runbook: 'When the rollback is done')."
fi

# Last, so the guidance above always prints: a caller reading only $? must not be told this rollback succeeded.
if [[ "$REPLAY_CHECK_FAILED" != "0" ]]; then
    echo >&2
    echo "ROLLBACK INCOMPLETE: the reverse-replay postcondition failed (see the WARNING above). Exiting non-zero." >&2
    exit 1
fi
