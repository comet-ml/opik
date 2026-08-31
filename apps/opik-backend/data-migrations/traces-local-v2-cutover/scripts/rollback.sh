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
# Or --sentinel-repair-only: restore NULL on the rows written into the still-Nullable original while
# traceColumnsNonNullable was true (they read back as an epoch/NaN sentinel, and its MATERIALIZED duration computed a
# large negative from them). It covers BOTH windows that mint them: after a stage B/C promote, and after a cutover
# abandoned before the EXCHANGE, which damages the live original just the same but has no promote to point at. Kept
# separate from the stages rather than appended to them because the flag revert must land on every backend FIRST —
# otherwise in-flight writes keep minting sentinels behind the repair — and rolling out config is outside these
# DB-facing scripts. Skips the mutation when there is nothing to repair, so it is safe to run speculatively. See
# 000004_rollback_sentinel_repair.sql.
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
#   --receive-timeout N       seconds clickhouse-client waits for the next packet from the server before giving up
#                             (SETTINGS receive_timeout). Default 1800, against ClickHouse's own 300. The sentinel
#                             repair blocks on mutations_sync = 2 until every replica has applied a whole-table
#                             rewrite, and that wait is unbounded server-side, so the client socket timeout is the
#                             only limit on it. Under the stock 300 a healthy repair on a large table is reported as
#                             a failure. The cost of a generous value is that a genuinely dead connection takes that
#                             long to surface.
#   --sentinel-repair-only    repair ONLY the epoch/NaN sentinels on the restored original (no promote, no replay, no
#                             rename). Requires --confirm-flag-reverted. Mutually exclusive with --stage,
#                             --reverse-replay-only and --unwrap-only.
#   --confirm-single-shard    Accepted only with --sentinel-repair-only, the only mode that asserts the shard count.
#                             Asserts this cluster has ONE shard where that count cannot be READ; it does NOT override a
#                             count that came back greater than 1, which stays fatal. Use it only where
#                             system.clusters / system.macros are genuinely unreadable and the topology is known, and
#                             note that the repair's postcondition reads clusterAllReplicas('{cluster}', ...), which
#                             needs the same macro — so it will not run either.
#   --confirm-flag-reverted   REQUIRED with --sentinel-repair-only, and accepted by no other mode. Asserts
#                             databaseAnalyticsDataModel.traceColumnsNonNullable=false is live on EVERY backend
#                             instance. The scripts cannot read backend config, and a repair run while any instance
#                             still has it true is not merely incomplete: that instance keeps writing fresh sentinels
#                             behind the mutation, so the counts can clear and then regress.
#   --sentinel-window-from TS / --sentinel-window-to TS
#                             REQUIRED with --sentinel-repair-only. 'YYYY-MM-DD HH:MM:SS[.ffffff]' in UTC, half-open
#                             [from, to). Both bounds are pinned to UTC in the SQL, so the window means the same thing
#                             whatever timezone the server runs in.
#                             The window traceColumnsNonNullable was live in. Only rows written inside it are repaired,
#                             matched on created_at OR last_updated_at — a row CREATED in the window by the first, a
#                             pre-existing row UPDATED in it (which is where its sentinel came from) by the second.
#                             Mandatory because an epoch end_time is not evidence the flag produced it: clients send
#                             them, and rows predating the flag hold them. An unbounded repair would set those to NULL
#                             with no way back, since the parked successor encodes an absent end_time as that same
#                             epoch, and the counts would still report success. Widen rather than guess: a row outside
#                             the window is simply left alone.
#   --confirm-flag-was-live   Only for --sentinel-repair-only, and only needed when there is NO parked successor: an
#                             abandoned pre-EXCHANGE cutover, or an estate finalize.sh has already recycled. Asserts
#                             traceColumnsNonNullable was live HERE, so the epoch/NaN values in `traces` are sentinels it
#                             minted rather than values a client sent, and accepts that those WITHIN THE WINDOW become
#                             NULL. It authorises a window-scoped repair, not a table-wide one: rows outside the window
#                             are never touched, whether or not this flag is passed. There is no topological proof of
#                             the assertion: `traces_local_v2` exists on every installation, so its presence
#                             establishes nothing.
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
CONFIRM_SINGLE_SHARD=0
RECEIVE_TIMEOUT=1800     # seconds tolerated between server packets, not total query time. See --receive-timeout.
CONFIRM_FLAG_WAS_LIVE=0  # only consulted by --sentinel-repair-only, and only without a parked successor.
SENTINEL_WINDOW_FROM=""  # required by --sentinel-repair-only; see --sentinel-window-from.
SENTINEL_WINDOW_TO=""
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
        --confirm-single-shard) CONFIRM_SINGLE_SHARD=1; shift ;;
        --confirm-flag-reverted) CONFIRM_FLAG_REVERTED=1; shift ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-flag-was-live) CONFIRM_FLAG_WAS_LIVE=1; shift ;;
        --sentinel-window-from) SENTINEL_WINDOW_FROM="${2:?"$1 requires a value"}"; shift 2 ;;
        --sentinel-window-to) SENTINEL_WINDOW_TO="${2:?"$1 requires a value"}"; shift 2 ;;
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
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }
for _w in "$SENTINEL_WINDOW_FROM" "$SENTINEL_WINDOW_TO"; do
    [[ -z "$_w" || "$_w" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] \
        || { echo "ERROR: --sentinel-window-from/--sentinel-window-to must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
done
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
if [[ "$CONFIRM_FLAG_WAS_LIVE" == "1" && "$SENTINEL_REPAIR_ONLY" != "1" ]]; then
    echo "ERROR: --confirm-flag-was-live belongs to --sentinel-repair-only and to no other mode." >&2
    exit 2
fi
# Only --sentinel-repair-only asserts the shard count, so the flag would be inert anywhere else. Reject it rather than
# accept it: an operator who passed it would reasonably believe the topology had been taken into account.
if [[ "$CONFIRM_SINGLE_SHARD" == "1" && "$SENTINEL_REPAIR_ONLY" != "1" ]]; then
    echo "ERROR: --confirm-single-shard belongs to --sentinel-repair-only and to no other mode." >&2
    exit 2
fi
if [[ "$CONFIRM_FLAG_REVERTED" == "1" && "$SENTINEL_REPAIR_ONLY" != "1" ]]; then
    echo "ERROR: --confirm-flag-reverted belongs to --sentinel-repair-only and to no other mode. Stages B/C run BEFORE" >&2
    echo "       traceColumnsNonNullable is reverted — reverting it is the next step they print — so asserting it here" >&2
    echo "       would assert the opposite of the sequence. Run the stage, land the revert, then re-run with" >&2
    echo "       --sentinel-repair-only --confirm-flag-reverted." >&2
    exit 2
fi
# The window is meaningless outside the repair -- nothing else reads it -- so reject it rather than ignore it.
if [[ ( -n "$SENTINEL_WINDOW_FROM" || -n "$SENTINEL_WINDOW_TO" ) && "$SENTINEL_REPAIR_ONLY" != "1" ]]; then
    echo "ERROR: --sentinel-window-from/--sentinel-window-to belong to --sentinel-repair-only and to no other mode." >&2
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
    if [[ -z "$SENTINEL_WINDOW_FROM" || -z "$SENTINEL_WINDOW_TO" ]]; then
        echo "ERROR: --sentinel-repair-only requires --sentinel-window-from and --sentinel-window-to: the window" >&2
        echo "       traceColumnsNonNullable was live in. There is no safe default. An epoch end_time is not evidence" >&2
        echo "       the flag produced it — clients send them, and rows predating the flag hold them — so an unbounded" >&2
        echo "       repair would set real values to NULL with no way back: the parked successor encodes an absent" >&2
        echo "       end_time as that same epoch, so nothing holds the original. Take the bounds from when the flag" >&2
        echo "       rolled out and when its revert finished landing everywhere; widening is safe, guessing is not." >&2
        exit 2
    fi
    if [[ ! "$SENTINEL_WINDOW_FROM" < "$SENTINEL_WINDOW_TO" ]]; then
        echo "ERROR: --sentinel-window-from must be strictly before --sentinel-window-to (the window is half-open)." >&2
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

# One place for the connection and client-side options, so the call sites below cannot drift — in particular so
# --receive-timeout covers the sentinel repair, whose mutations_sync = 2 wait is bounded only by this timeout.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT")

ch() {
    clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_rollback' --query "$1"
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

# The sentinel repair accepts a SECOND state the reverse replay must not, so it gets its own assertion rather than a
# widened shared one. Both windows the runbook documents put sentinels in the still-Nullable original: the promote-to-
# flag-revert window (post-rollback), AND the flip-to-EXCHANGE window, where the cutover may be abandoned and there is
# no promote to point at.
#
# Only the first is TOPOLOGICALLY provable. `traces_post_rollback_backup` carrying the successor schema exists solely
# because a promote created it. There is no equivalent signal for the pre-EXCHANGE case — in particular NOT the presence
# of `traces_local_v2`, which Liquibase creates unconditionally on every installation (000101), so it says nothing about
# whether a cutover was attempted here. Nor does the data distinguish an epoch this flag minted from one a client
# genuinely sent, which is what makes a table-wide rewrite dangerous on an estate that never flipped it. So the operator
# asserts it, and only where the proof is unavailable: --confirm-flag-was-live.
#
# The reverse replay cannot accept the pre-EXCHANGE state at all: without a completed promote it masks live rows.
# The repair mutates the shard it is connected to (it deliberately avoids ON CLUSTER, so it travels by replication,
# which spans a shard's replicas and not other shards), while its postcondition reads clusterAllReplicas, which spans
# every shard. On more than one shard those two scopes disagree: the mutation would fix one shard and the check would
# keep failing, after a whole-table rewrite that could not have satisfied it. Refuse up front instead — the wasted work
# is the point, not the confusing verdict. The reverse replay shares the single-shard assumption; this is the mode where
# the mismatch became observable, because it is the one that verifies across the cluster.
assert_single_shard() {
    local shards
    shards="$(ch "SELECT uniqExact(shard_num) FROM system.clusters
                  WHERE cluster = (SELECT substitution FROM system.macros WHERE macro = 'cluster')" 2>/dev/null || true)"
    if [[ "$shards" =~ ^[0-9]+$ ]] && (( shards > 1 )); then
        echo "ERROR: this cluster reports $shards shards. The repair reaches only the shard you are connected to, while" >&2
        echo "       its postcondition reads every shard, so it would rewrite one shard and then report failure." >&2
        echo "       Run it once per shard, connecting to a replica of each with --host, and treat the postcondition as" >&2
        echo "       satisfied only after the last one clears." >&2
        exit 1
    fi
    # Unreadable is fatal unless the operator asserts the topology. Assuming the safe case defeats the point of the
    # check: it exists to stop a whole-table rewrite whose postcondition cannot be satisfied, and that is exactly the
    # run that would proceed. Nor does proceeding buy anything — the postcondition reads
    # clusterAllReplicas('{cluster}', ...), which needs the same system.macros this count needs, so a session that
    # cannot read the shard count cannot verify the repair either.
    if ! [[ "$shards" =~ ^[0-9]+$ ]]; then
        if [[ "$CONFIRM_SINGLE_SHARD" == "1" ]]; then
            echo "NOTE: could not read the shard count; proceeding on --confirm-single-shard. If the cluster in fact has" >&2
            echo "      more than one shard, this rewrites only the shard you are connected to." >&2
            return 0
        fi
        echo "ERROR: could not read the shard count (needs SELECT on system.clusters and system.macros), so this cluster's" >&2
        echo "       topology is unknown. This mode reaches only the shard you are connected to while its postcondition" >&2
        echo "       reads every shard, so on more than one shard it would rewrite one and then report failure." >&2
        echo "       Grant the reads, or pass --confirm-single-shard to assert the topology yourself." >&2
        exit 1
    fi
}

assert_sentinel_repair_state() {
    local traces_end_time backup_end_time
    traces_end_time="$(traces_endtime_type traces)"
    backup_end_time="$(traces_endtime_type traces_post_rollback_backup)"
    if [[ "$traces_end_time" != Nullable* ]]; then
        echo "ERROR: --sentinel-repair-only needs 'traces' on the ORIGINAL schema (Nullable end_time); observed" >&2
        echo "       end_time='${traces_end_time:-<absent>}'. On the successor the epoch/NaN values are that schema's own" >&2
        echo "       encoding of an absent value, not damage. Roll the cutover back first." >&2
        exit 1
    fi
    if [[ -n "$backup_end_time" && "$backup_end_time" != Nullable* ]]; then
        return 0   # a completed promote parked the successor here; nothing left to assert
    fi
    if [[ "$CONFIRM_FLAG_WAS_LIVE" == "1" ]]; then
        echo "NOTE: no parked successor, so proceeding on --confirm-flag-was-live: you assert traceColumnsNonNullable was" >&2
        echo "      live on THIS estate, and accept that every epoch end_time and NaN ttft WITHIN THE SUPPLIED WINDOW" >&2
        echo "      becomes NULL. Rows outside it are not touched." >&2
        return 0
    fi
    echo "ERROR: --sentinel-repair-only found no parked successor ('traces_post_rollback_backup' carrying the successor" >&2
    echo "       schema), which is the only proof a cutover ran here. Observed end_time='${backup_end_time:-<absent>}'." >&2
    echo "       Without it this cannot tell a sentinel the flag minted from an epoch a client genuinely sent, and the" >&2
    echo "       repair rewrites the whole table — so on an estate that never flipped the flag it would destroy real" >&2
    echo "       values. 'traces_local_v2' is NOT evidence either way: Liquibase creates it on every installation." >&2
    echo "       If the cutover was abandoned before the EXCHANGE, or finalize.sh has recycled the backup, and you know" >&2
    echo "       the flag was live here, re-run with --confirm-flag-was-live to accept that." >&2
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
    resurrected="$(clickhouse-client "${CH_ARGS[@]}" --query "$sql")"
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

# Same sourcing contract as run_file (versioned .sql), with the database and both window bounds substituted. Returns the
# four counts as one tab-separated line so the driver gates on them, instead of leaving numbers on a screen to read.
# Bounds default to the operator's window; pass them explicitly to read the same aggregates over a wider range, which is
# how the unbounded comparison below comes from this one file rather than a second copy of the predicates.
# Called only inside a command substitution, so it must not try to report its own failures: an exit here ends the
# subshell, not the script. The caller validates the file up front and treats unusable output as unverified.
sentinel_counts() {
    local from="${1:-$SENTINEL_WINDOW_FROM}" to="${2:-$SENTINEL_WINDOW_TO}"
    local file="$SQL_DIR/000004_rollback_verify_sentinels.sql" sql
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${SENTINEL_WINDOW_FROM}'/$from}"
    sql="${sql//'${SENTINEL_WINDOW_TO}'/$to}"
    clickhouse-client "${CH_ARGS[@]}" --query "$sql"
}

run_file() {
    local file="$SQL_DIR/$1" sql
    [[ -f "$file" ]] || { echo "ERROR: cannot find $file" >&2; exit 2; }
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    sql="${sql//'${SENTINEL_WINDOW_FROM}'/$SENTINEL_WINDOW_FROM}"
    sql="${sql//'${SENTINEL_WINDOW_TO}'/$SENTINEL_WINDOW_TO}"
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
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
        "The reverse-replay carries no resurrection guard, so if aimed at the live successor (EXCHANGE ran but no" \
        "rollback did) it would mask deleted-then-recreated rows — silent data loss. Use this mode only when a stage" \
        "B/C promote succeeded but its replay did not."
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
    # Validated here, not inside sentinel_counts: that function is only ever called in a command substitution, where an
    # exit terminates the subshell and the caller reports it as an unreadable count, i.e. the wrong cause.
    for sentinel_sql in 000004_rollback_sentinel_repair.sql 000004_rollback_verify_sentinels.sql; do
        [[ -f "$SQL_DIR/$sentinel_sql" ]] || { echo "ERROR: cannot find $SQL_DIR/$sentinel_sql" >&2; exit 2; }
    done
    assert_sentinel_repair_state
    assert_single_shard
    echo "NOTE: repairing epoch/NaN sentinels on the restored original. Nothing is promoted, replayed or renamed — this" >&2
    echo "      is one mutation on the table that is already live, and it recomputes 'duration' from the restored NULL." >&2

    # Read first: it sizes the work, and a clean table skips the mutation entirely (so the mode is safe to run
    # speculatively). Empty or non-numeric output is a dead client, not a clean table — never let that read as success.
    if ! sentinel_before="$(sentinel_counts)"; then sentinel_before=""; fi
    read -r before_end_time before_ttft before_negative before_stale <<< "$sentinel_before"
    if ! [[ "$before_end_time" =~ ^[0-9]+$ && "$before_ttft" =~ ^[0-9]+$ && "$before_stale" =~ ^[0-9]+$ ]]; then
        echo "ERROR: the sentinel counts could not be read, so it is unknown whether there is anything to repair." >&2
        echo "       Treat this as unverified, not as clean. Fix connectivity and re-run — no mutation was issued." >&2
        exit 1
    fi
    # Same aggregates over an all-time range. A 0 inside the window against a non-zero total is the signature of a wrong
    # window -- bounds in local time, a typo -- which otherwise reports as a clean estate and exits 0 over unrepaired rows.
    #
    # Only the CEILING is wrong in the range this used to pass (1900..2299). `created_at` is DateTime64(9), whose ceiling
    # is 2262-04-11 23:47:16.854775807, so a 2299 literal is promoted to precision 9 for the comparison and raises
    # DECIMAL_OVERFLOW (Code 407) every time -- which made this whole comparison dead code on every real schema, printing
    # '?' where the operator was told to read a number.
    #
    # The floor stays 1900-01-01, though NOT because that is the type's minimum: DateTime64(9) does represent
    # 1677-09-21 00:12:44 onwards, per the docs, and a value built from ticks round-trips there exactly. The reason is
    # that this bound reaches the server as a STRING, and toDateTime64('<string>', N) cannot express anything earlier --
    # it clamps the DATE to 1900-01-01 while KEEPING the time of day, silently and in the wrong direction:
    # '1677-09-21 00:12:44' becomes 1900-01-01 00:12:44 (12m44s later than the plain floor) and '1899-12-31 23:59:59'
    # becomes 1900-01-01 23:59:59 (nearly a day later than asked). Reaching the true minimum would take
    # fromUnixTimestamp64Nano() in place of the substituted literal, which this file shares with the windowed gate where
    # the bound has to stay an operator-supplied datetime. Immaterial regardless: nothing in these columns predates 1900.
    #
    # The ceiling costs the `created_at` arm nothing, since the column cannot hold a later value; it costs the
    # `last_updated_at` arm (DateTime64(6)) only 2262..2299, unreachable for a column set from now64(). Far-future values
    # in this dataset are far-future *ids*, which drive the successor's `id_at` partitioning -- not `created_at`, which an
    # earlier revision of this comment conflated them with.
    if ! sentinel_all="$(sentinel_counts '1900-01-01 00:00:00' '2262-04-11 23:47:16.854775807')"; then sentinel_all=""; fi
    read -r all_end_time all_ttft _ _ <<< "$sentinel_all"
    [[ "$all_end_time" =~ ^[0-9]+$ ]] || { all_end_time="?"; all_ttft="?"; }

    echo "Sentinels IN WINDOW [$SENTINEL_WINDOW_FROM, $SENTINEL_WINDOW_TO): end_time=$before_end_time ttft=$before_ttft (of those, serving a negative duration: $before_negative)"
    echo "Same counts with no window, for comparison: end_time=$all_end_time ttft=$all_ttft"
    # '?' means the comparison did not run, which is not the same as it running and agreeing. Say so, rather than leaving a
    # bare '?' on a line the runbook points operators at: a check that silently did not execute reads as one that passed.
    if [[ "$all_end_time" == "?" ]]; then
        echo "NOTE: the no-window comparison returned no usable number, so it is not evidence either way -- neither that the" >&2
        echo "      window is right nor that it is wrong. The gate remains the IN-WINDOW count, and the selected rows still" >&2
        echo "      have to be reconciled by identity before a clean read is trusted." >&2
    fi
    if (( before_end_time == 0 && before_ttft == 0 )); then
        if [[ "$all_end_time" =~ ^[0-9]+$ ]] && [[ "$all_end_time" != "0" || "$all_ttft" != "0" ]]; then
            echo "WARNING: nothing matches INSIDE the window, but the table holds end_time=$all_end_time ttft=$all_ttft" >&2
            echo "         outside it. That is what a wrong window looks like, and bounds in local time rather than UTC is" >&2
            echo "         the common case. Confirm the bounds are the window the flag was live in, in UTC, before reading" >&2
            echo "         this as a clean estate. If they are, those rows are not this flag's doing and are correctly" >&2
            echo "         left alone." >&2
        fi
        if (( before_stale == 0 )); then
            echo "Nothing to repair inside the window: no row there carries an epoch end_time or a NaN ttft. No mutation issued."
            exit 0
        fi
        # A negative duration on a row whose end_time is NULL cannot happen while `duration` is MATERIALIZED, and this
        # mutation cannot fix it: with no sentinel left to match, it would rewrite nothing. Needs a human, not a re-run.
        echo "WARNING: no sentinel remains, but $before_stale row(s) have a negative duration with a NULL end_time, which" >&2
        echo "         the materialized expression cannot produce. 'duration' is not being recomputed on rewrite — check" >&2
        echo "         that it is still a MATERIALIZED column. This repair matches on the sentinel, so it cannot correct" >&2
        echo "         them. No mutation issued." >&2
        exit 1
    fi

    if ! run_file 000004_rollback_sentinel_repair.sql; then
        echo >&2
        echo "ERROR: the sentinel repair did not complete. Read the ClickHouse error above before re-running: the two" >&2
        echo "       likely causes need opposite responses." >&2
        echo "  ACCESS_DENIED — the expected failure for a user scoped to the rollback grant set, which holds only" >&2
        echo "       ALTER UPDATE(_row_exists). This statement needs ALTER UPDATE(end_time) AND ALTER UPDATE(ttft) on" >&2
        echo "       'traces', and carries both commands in one mutation, so a missing grant on either applies neither." >&2
        echo "       Nothing is half-repaired. Grant the two columns (or use a more privileged user), re-run, revoke." >&2
        echo "  A TIMEOUT — the mutation is very likely still running and healthy; only the client gave up waiting. Do" >&2
        echo "       NOT re-run blind, which queues a second whole-table rewrite behind the first. Check for an" >&2
        echo "       unfinished mutation on 'traces' in system.mutations, let it finish, then re-run this mode to read" >&2
        echo "       the postcondition. Raise --receive-timeout (currently ${RECEIVE_TIMEOUT}s) for the next attempt." >&2
        exit 1
    fi

    if ! sentinel_after="$(sentinel_counts)"; then sentinel_after=""; fi
    read -r after_end_time after_ttft after_negative after_stale <<< "$sentinel_after"
    if ! [[ "$after_end_time" =~ ^[0-9]+$ && "$after_ttft" =~ ^[0-9]+$ && "$after_stale" =~ ^[0-9]+$ ]]; then
        echo "WARNING: sentinel postcondition COULD NOT BE EVALUATED — the counts came back unreadable. The mutation" >&2
        echo "         reported success, but treat the repair as unverified: re-run this mode to re-read the counts." >&2
        SENTINEL_CHECK_FAILED=1
    elif (( after_end_time == 0 && after_ttft == 0 && after_stale == 0 )); then
        echo "Sentinel postcondition OK: end_time=0 ttft=0 stale_duration=0 on every replica, INSIDE the window"
        echo "[$SENTINEL_WINDOW_FROM, $SENTINEL_WINDOW_TO). Rows outside it were never in scope."
        echo "Repaired $before_end_time end_time and $before_ttft ttft value(s); 'duration' recomputed from the restored NULL."
        echo "A residual count of negative durations elsewhere in the table is EXPECTED and is not this repair's business:"
        echo "rows whose end_time genuinely precedes start_time are a pre-existing source artifact and stay negative."
    else
        echo "WARNING: sentinel postcondition FAILED — end_time=$after_end_time ttft=$after_ttft stale_duration=$after_stale" >&2
        echo "         after the mutation reported success." >&2
        if (( after_end_time > 0 || after_ttft > 0 )); then
            echo "         A remaining sentinel most likely means a backend instance is still running with" >&2
            echo "         traceColumnsNonNullable=true and minting fresh ones behind the repair; --confirm-flag-reverted" >&2
            echo "         asserts that is not so but cannot check it. Confirm the revert is live on EVERY instance, then" >&2
            echo "         re-run this mode (idempotent)." >&2
        fi
        if (( after_stale > 0 )); then
            echo "         A non-zero stale_duration means the rewrite did NOT recompute 'duration': rows now have a" >&2
            echo "         NULL end_time and a negative duration, which the materialized expression cannot produce." >&2
            echo "         Re-running will not help — check that 'duration' is still a MATERIALIZED column." >&2
        fi
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
        echo
        echo "BUT the live 'traces' may still need repairing before you walk away. traceColumnsNonNullable was rolled"
        echo "out before the EXCHANGE, so any trace written while it was true carries an epoch/NaN sentinel and a large"
        echo "negative duration in this table — untouched by stage A, which only discarded the shadow. If you are"
        echo "abandoning the cutover rather than retrying it:"
        echo "  1. Set databaseAnalyticsDataModel.traceColumnsNonNullable=false and roll-restart every backend instance."
        echo "  2. ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --sentinel-repair-only --confirm-flag-reverted --confirm-flag-was-live \\"
        echo "       --sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>'"
        echo "     Fill both bounds in before running: they are mandatory and this script cannot know them. Only rows"
        echo "     written inside the window are repaired, so widening is safe and guessing is not."
        echo "     --confirm-flag-was-live is required HERE and not after a stage B/C promote: stage A parks no successor,"
        echo "     and the parked successor is the only topological proof a cutover ran on this estate. Without it the"
        echo "     repair cannot tell a sentinel this flag minted from an epoch a client sent, so you assert it."
        echo "     Order matters only between those two: repairing while any instance still has the flag true lets it"
        echo "     mint fresh sentinels behind the mutation. Stage A itself may come before or after — it TRUNCATEs the"
        echo "     shadow rather than dropping it, so the evidence the repair needs survives either way."
        echo "     It reports 'nothing to repair' and exits 0 if no trace landed in that window, so it is safe to run."
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
    echo "       ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --sentinel-repair-only --confirm-flag-reverted \\"
    echo "         --sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>'"
    echo "       Both bounds are mandatory and this script cannot know them; fill them in before running."
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
