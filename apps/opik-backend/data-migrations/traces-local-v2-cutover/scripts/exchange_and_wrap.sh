#!/usr/bin/env bash
#
# Driver for step 3 of the traces cutover: settle gate + EXCHANGE + Distributed wrap (runbook: ../README.md).
#
# Runs the replication-settle gate, captures and prints cutover_start (needed by rollback.sh if you roll back after
# this), then runs the `exchange` block of db-app-analytics/000003_exchange_and_wrap.sql. By default it stops there
# (EXCHANGE only) — the Distributed `wrap` block runs only with --with-wrap. Every statement it sends comes from a
# marked block of that file (the final deletion replay from 000002's, so the two runs share one source); only the short
# topology probes below are inline. Run it right after the delta + replay + verify, so the final-delta -> EXCHANGE gap
# stays small. Nothing here needs an ingestion-side config change: the EXCHANGE is atomic per node, so a concurrent
# insert always commits to a valid table. Writes that land in the old one — in that gap, or during the cross-node
# ON CLUSTER skew — stay in the parked backup; that is the open tail write-gap tracked as OPIK-8238, and the runbook's
# "The final cutover window" states the exposure.
#
# The wrap is OPT-IN on purpose: a lightweight DELETE against a Distributed table is unsupported, so wrapping `traces`
# breaks the product's trace-delete / retention paths unless tracesDistributedWrapEnabled=true (OPIK-7455) routes those
# mutations at `traces_local`. The safe default is to leave `traces` a MergeTree (deletes keep working) and apply the
# wrap later, flipping that toggle in lockstep.
#
# Guarded like rollback.sh: it asserts the live `traces` topology matches the requested action before touching anything,
# so a re-run cannot silently swap the tables back, and a partial EXCHANGE (swap done, post-swap RENAME not) is detected
# with the command to finish it.
#
# Connection: CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment, plus --host and --port. CLICKHOUSE_PORT is
# NOT honored by clickhouse-client, and CLICKHOUSE_HOST is honored only when no connection flag is given, so pass
# --host and --port together. The user must be able to set `log_comment` (used for cutover attribution in
# query_log): a `readonly = 1` profile rejects it outright ("Cannot modify 'log_comment' setting in readonly mode"),
# so a read-only assessor needs `readonly = 2` and the migration user needs a non-readonly profile.
#
# Options:
#   --database NAME   analytics database (e.g. opik). Required.
#   --port N                  ClickHouse NATIVE port, when it is not the default 9000 — e.g. reaching a cluster through
#                             a port-forward or bastion on a local port. Required because clickhouse-client honors
#                             CLICKHOUSE_HOST / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment but does
#                             NOT honor CLICKHOUSE_PORT, so the port cannot be passed via env.
#   --host HOST               ClickHouse host. Pass it together with --port: clickhouse-client honors CLICKHOUSE_HOST
#                             ONLY when no connection flag is given, so supplying --port alone silently reverts the host
#                             to localhost. User/password still come from CLICKHOUSE_USER / CLICKHOUSE_PASSWORD (keeping
#                             the password out of argv).
#   --receive-timeout N       seconds clickhouse-client waits for the NEXT PACKET before giving up (receive_timeout).
#                             Default 1800, against ClickHouse's own 300, which bounds the GAP between packets rather
#                             than total query time — so a step that goes quiet while the server works trips it while
#                             healthy. In this driver it also sets distributed_ddl_task_timeout, which is the binding
#                             limit here — see the CH_ARGS comment below, and ../README.md for the trade-off.
#   --backfill-start TS  the anchor printed by backfill.sh. REQUIRED for every EXCHANGE path (not --wrap-only): just
#                     before the swap this runs a final deletion replay from that anchor, so deletes bridged since the
#                     last delta_replay.sh don't leak live across the EXCHANGE (they'd be covered by neither the forward
#                     replay nor the rollback reverse-replay otherwise). Must carry an explicit ' UTC' marker, as the
#                     drivers print it; the value is parsed as UTC, so without it its zone is unknown.
#   (default)         run ONLY the EXCHANGE (the data cutover), then stop — leaves `traces` a MergeTree where deletes
#                     still work. The Distributed wrap is deferred (see above).
#   --with-wrap       also apply the Distributed wrap in the same run (EXCHANGE + wrap). Use only once
#                     tracesDistributedWrapEnabled=true (OPIK-7455) is live so trace mutations target `traces_local`.
#                     Mutually exclusive with --skip-wrap / --wrap-only.
#   --skip-wrap       explicit alias for the default (EXCHANGE only); accepted for clarity and back-compat.
#   --wrap-only       run ONLY the Distributed wrap on the already-swapped `traces` (no EXCHANGE, no new cutover_start)
#                     — the deferred second half of a prior EXCHANGE-only run. Mutually exclusive with the above.
#   --settle-timeout N        seconds the replication-settle gate polls before giving a verdict. Default 120, capped at
#                             3600. The gate does not demand an instantaneous zero: under live ingestion the
#                             replication_queue is intermittently non-zero by construction (a GET_PART entry per
#                             not-yet-fetched part), so a single sample would abort on ordinary churn. Raise it for a
#                             slow-but-progressing cluster — it delays the EXCHANGE and so lengthens the tail write-gap
#                             by the wait, which the driver reports. See assert_replication_settled for the two
#                             judgements it makes.
#   --force           skip the replication-settle gate entirely. By default the swap aborts when the deletion-replay
#                     mutation has not finished on every replica, or when the replication queue is not merely busy but
#                     stuck (an aged entry, retries, or a last_exception) — either way a behind replica would swap in an
#                     incomplete table. Use only if settlement is confirmed out of band; the runbook's Go/No-Go forbids
#                     it in production.
#   --confirm-maintenance  REQUIRED whenever the wrap is applied (--with-wrap or --wrap-only). The wrap is gapless per
#                     node (atomic rotate), but a brief cross-node ON CLUSTER propagation skew remains, during which a
#                     Distributed query can hit a not-yet-created `traces_local` on a lagging node and fail. The failing
#                     query is a SELECT, so no ingestion-side setting reduces it and both wrap paths carry it equally.
#                     Assert that traffic is quiesced or a maintenance window is in effect. Mirrored by
#                     rollback.sh --unwrap-only, which reverses this.
#   --confirm-daos-retargeted  REQUIRED whenever the wrap is applied (--with-wrap or --wrap-only). Asserts the trace
#                     delete/mutation DAOs already target `traces_local`: set backend config
#                     databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true (OPIK-7455) in lockstep with the wrap.
#                     A Distributed `traces` rejects mutations, so without the flag delete-by-id and retention deletes
#                     return 500 the moment the wrap lands. The script cannot inspect backend config, so the operator
#                     must assert it.
#   --confirm-retention-paused  REQUIRED for every EXCHANGE path. Retention deletes (deleteForRetention*) bypass the
#                     deletion bridge and are never replayed onto the successor, so a retention sweep during the cutover
#                     window leaks live across the swap. Asserts retention is paused (RETENTION_ENABLED=false on every
#                     backend) for the whole window — a backend setting the script can't read.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000003_exchange_and_wrap.sql"
DELTA_SQL_FILE="$SCRIPT_DIR/db-app-analytics/000002_delta_and_deletion_replay.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
BACKFILL_START=""
SKIP_WRAP=0
WITH_WRAP=0
WRAP_ONLY=0
FORCE=0
SETTLE_TIMEOUT=120        # seconds the settle gate polls before deciding. See --settle-timeout.
SETTLE_TIMEOUT_MAX=3600   # its accepted ceiling; the validation below explains why the check is lexical.
CONFIRM_MAINTENANCE=0
CONFIRM_DAOS_RETARGETED=0
CONFIRM_RETENTION_PAUSED=0

# Stuck-ness thresholds for the replication queue, deliberately not flags: they describe what "a replica is genuinely
# lagging" means, not a per-run choice. An entry that has sat this long, or retried this many times, is not the
# ordinary GET_PART churn of a busy table.
SETTLE_POLL_SECONDS=5
SETTLE_STUCK_AGE_SECONDS=60
SETTLE_STUCK_NUM_TRIES=3

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --skip-wrap) SKIP_WRAP=1; shift ;;
        --with-wrap) WITH_WRAP=1; shift ;;
        --wrap-only) WRAP_ONLY=1; shift ;;
        --force) FORCE=1; shift ;;
        --settle-timeout) SETTLE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-maintenance) CONFIRM_MAINTENANCE=1; shift ;;
        --confirm-daos-retargeted) CONFIRM_DAOS_RETARGETED=1; shift ;;
        --confirm-retention-paused) CONFIRM_RETENTION_PAUSED=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }
# Both halves matter, and the digit cap has to come first, before any arithmetic sees the value. A leading zero would be
# read as octal ("value too great for base" once the gate divides by the poll interval). Past 2^63 bash arithmetic wraps
# silently instead of erroring: `polls` in the gate below goes negative, its `for` loop runs zero times, and the verdict
# then reads the unset sample variables as 0 and passes — so an out-of-range value would reach the EXCHANGE without
# having read replication at all. A numeric `<= SETTLE_TIMEOUT_MAX` test cannot catch that, because the wrapped value is
# negative and compares as in-range. 0 is accepted, and means a single sample.
[[ "$SETTLE_TIMEOUT" =~ ^(0|[1-9][0-9]{0,3})$ ]] && (( SETTLE_TIMEOUT <= SETTLE_TIMEOUT_MAX )) || { echo "ERROR: --settle-timeout must be an integer between 0 and $SETTLE_TIMEOUT_MAX seconds, with no leading zero; 0 takes a single sample. An hour of pre-swap polling is already past the point of aborting and resolving the lag." >&2; exit 2; }

# One place for the connection and client-side options, so every call site below carries the same host, port,
# database and the two timeouts. log_comment is NOT here, because clickhouse-client rejects a setting passed twice and
# only some of these statements take it from the client at all: the blocks in 000003 and 000002 set their own, by a
# leading SET and a trailing SETTINGS respectively, and both beat a client-level value. So the plain queries pass the
# flag per call, and run_final_deletion_replay substitutes the tag into the SQL instead.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
# distributed_ddl_task_timeout as well as receive_timeout, because everything this driver executes through run_block
# is ON CLUSTER DDL and that wait is capped server-side (180s by default, with distributed_ddl_output_mode = 'throw'),
# not by the client socket. Raising only receive_timeout would leave the one statement pair where a timeout costs most
# still bounded at the default: the EXCHANGE and its post-swap RENAME are a single --multiquery call, so a
# TIMEOUT_EXCEEDED between them leaves the split state assert_pre_exchange_topology diagnoses (traces already holds the
# successor while traces_local_v2 still exists) while the DDL keeps running in the background.
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT" \
          --distributed_ddl_task_timeout="$RECEIVE_TIMEOUT")
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }
[[ -f "$DELTA_SQL_FILE" ]] || { echo "ERROR: cannot find $DELTA_SQL_FILE" >&2; exit 2; }
# --backfill-start (the anchor printed by backfill.sh) is interpolated into the final deletion replay; validate its shape.
# Strip the ' UTC' marker the flag is required to carry (see its option doc). For these bounds a wrong zone is worse
# than a wrong shape: the statements parse the anchor as UTC, so one captured elsewhere shifts silently, and a LATER
# value drops rows from the delta and the replay rather than failing.
case "$BACKFILL_START" in
    *" UTC")
        BACKFILL_START="${BACKFILL_START% UTC}"
        # A bare marker strips to empty, which elsewhere means "not supplied" — two meanings for one value, and
        # the later "required" diagnostic would point away from the actual mistake.
        [[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start has no timestamp before the ' UTC' marker." >&2; exit 2; }
        ;;
        "") ;;                      # not supplied; the caller decides whether that is allowed
    *)
        echo "ERROR: --backfill-start must carry an explicit ' UTC' marker, as the drivers print it:" >&2
        echo "       --backfill-start '<YYYY-MM-DD HH:MM:SS[.ffffff]> UTC'" >&2
        echo "       The value is parsed as UTC; without the marker the zone it was captured in is unknown." >&2
        exit 2
        ;;
esac
[[ -z "$BACKFILL_START" || "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
# At most one wrap mode. Default (none set) is EXCHANGE only.
if (( SKIP_WRAP + WITH_WRAP + WRAP_ONLY > 1 )); then
    echo "ERROR: --skip-wrap, --with-wrap and --wrap-only are mutually exclusive" >&2; exit 2
fi
# The wrap is gapless per node but has a brief cross-node ON CLUSTER propagation skew, in which a Distributed query can
# reach a node where `traces_local` does not exist yet. The failing query is a SELECT, so nothing done on the ingestion
# side reduces it, and running in the same session as the EXCHANGE does not either: --with-wrap and --wrap-only carry
# the same exposure and the same requirement. Refuse (fail fast, before touching ClickHouse) unless the operator asserts
# ingestion is quiesced / a maintenance window is in effect.
if [[ ( "$WITH_WRAP" == "1" || "$WRAP_ONLY" == "1" ) && "$CONFIRM_MAINTENANCE" != "1" ]]; then
    echo "ERROR: applying the wrap requires --confirm-maintenance (both --with-wrap and --wrap-only). The wrap has a brief" >&2
    echo "       cross-node ON CLUSTER window in which a Distributed query can reach a node where 'traces_local' does not" >&2
    echo "       exist yet and fail — a READ exposure as much as a write one, which no ingestion-side setting covers." >&2
    echo "       Quiesce traffic or take a maintenance window, then re-run with it." >&2
    exit 2
fi
# HARD PREREQUISITE (OPIK-7455): a Distributed table rejects mutations, so once the wrap is applied the product's
# delete-by-id and retention deletes return 500 against `traces` unless those DAO paths already target `traces_local`.
# The script can't inspect backend config, so any wrap-applying mode must assert it. Fail fast, before touching ClickHouse.
if [[ ( "$WITH_WRAP" == "1" || "$WRAP_ONLY" == "1" ) && "$CONFIRM_DAOS_RETARGETED" != "1" ]]; then
    echo "ERROR: applying the wrap requires --confirm-daos-retargeted. Set backend config" >&2
    echo "       databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true (OPIK-7455) so the trace delete/mutation" >&2
    echo "       DAOs target 'traces_local' before 'traces' becomes Distributed, or deletes/retention break at runtime." >&2
    exit 2
fi
# The EXCHANGE runs a final deletion replay first (see below), to mask deletes bridged since the last delta_replay so
# they don't leak live across the swap — that needs the same backfill_start anchor delta_replay.sh used.
if [[ "$WRAP_ONLY" != "1" && -z "$BACKFILL_START" ]]; then
    echo "ERROR: the EXCHANGE requires --backfill-start (the anchor printed by backfill.sh) for the final deletion replay." >&2
    exit 2
fi
# Retention deletes (TraceDAO.deleteForRetention*) bypass the deletion bridge, so they are never replayed onto the
# successor — if any backend still has RETENTION_ENABLED=true, a retention sweep in the cutover window leaks live across
# the swap. Retention is a backend setting the script can't read, so require the operator to assert it is paused for the
# whole window. Applies to every EXCHANGE path (not --wrap-only, which does no data cutover).
if [[ "$WRAP_ONLY" != "1" && "$CONFIRM_RETENTION_PAUSED" != "1" ]]; then
    echo "ERROR: the EXCHANGE requires --confirm-retention-paused. Retention deletes bypass the deletion bridge, so a" >&2
    echo "       retention sweep during the cutover window would leak live across the swap. Pause retention" >&2
    echo "       (RETENTION_ENABLED=false on every backend) for the whole window, then re-run with the flag." >&2
    exit 2
fi

ch() {
    clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:exchange_and_wrap' --query "$1"
}

# Same connection, but rendered for a human — used only for the settle gate's detail blocks, whose interesting columns
# are free text (last_exception, postpone_reason, latest_fail_reason) that must not be parsed.
ch_vertical() {
    clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:exchange_and_wrap' \
        --format Vertical --query "$1"
}

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block (exact-line markers) from a reference SQL file.
extract() {
    awk -v begin="-- >>> BEGIN $1" -v end="-- >>> END $1" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$2"
}

# Refuse rendered SQL that is not what the caller asked for. A marker renamed, moved, indented or split yields text
# that is empty or only the block's own comments, and clickhouse-client exits 0 on either, so `set -e` never fires and
# the step prints its success line having done nothing.
#
# Masking comes first because every check needs the executable text: comments are not whitespace, and a block's prose
# can contain the very phrase that identifies it. The identity check is not redundant with the emptiness one -- markers
# can match around the wrong statement, which is plenty of text.
#
# RETURN, not exit: `render` calls this inside a command substitution, where an exit would end only that subshell and
# hand the caller partial SQL. Every caller assigns first and adds `|| exit 2`, so a refusal still stops the run.
require_rendered() {
    local sql="$1" what="$2" must_contain="$3" file="$4" masked begins ends
    # Exactly one pair, checked on the file rather than the extraction: the awk stops at the first END and otherwise
    # runs to EOF, so a missing or renamed END sweeps every later block into this one and --multiquery executes them
    # all. The content checks below cannot see that -- a run-on capture still contains this block's own statement.
    begins="$(grep -cxF -e "-- >>> BEGIN $what" "$file" || true)"
    ends="$(grep -cxF -e "-- >>> END $what" "$file" || true)"
    if (( begins != 1 || ends != 1 )); then
        echo "ERROR: $file holds $begins '-- >>> BEGIN $what' and $ends '-- >>> END $what'; expected one of each." >&2
        return 2
    fi
    masked="$(sed 's/--.*$//' <<<"$sql")"
    if [[ -z "${masked//[[:space:]]/}" ]]; then
        echo "ERROR: the '$what' block from $file rendered no executable SQL (empty, or comments only)." >&2
        echo "       Expected the exact marker lines '-- >>> BEGIN $what' and '-- >>> END $what'." >&2
        return 2
    fi
    if ! grep -qF "$must_contain" <<<"$masked"; then
        echo "ERROR: the '$what' block from $file has no '$must_contain' outside its comments, so the markers are" >&2
        echo "       around the wrong statement. Refusing to run it." >&2
        return 2
    fi
    if grep -qF '${' <<<"$masked"; then
        echo "ERROR: the '$what' block from $file still holds an unsubstituted \${...} placeholder after rendering." >&2
        echo "       Refusing to send SQL containing a literal placeholder." >&2
        return 2
    fi
}

# render <block> <file> <must-contain-token> -> the block's SQL, substituted and validated.
#
# BACKFILL_START is substituted only when set, so a block that needs it and runs without it keeps the literal
# placeholder and is refused by require_rendered — rather than being sent with an empty timestamp, which parses and
# silently matches the wrong window. '{cluster}' is NOT a placeholder: it is the ClickHouse macro, resolved server-side.
render() {
    local sql
    sql="$(extract "$1" "$2")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    [[ -z "$BACKFILL_START" ]] || sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
    require_rendered "$sql" "$1" "$3" "$2" || return 2
    printf '%s' "$sql"
}

# Single scalar (empty string if the object does not exist).
traces_engine() {
    ch "SELECT engine FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}
traces_endtime_type() {
    ch "SELECT type FROM system.columns WHERE database = '$DATABASE' AND table = '$1' AND name = 'end_time'"
}

# Topology precondition. The EXCHANGE must run ONLY against the pre-EXCHANGE estate (traces = original schema). Running
# it a second time would silently swap the tables back — the successor gets parked and the old original goes live again,
# with no error — so this refuses instead. It also detects the split state where the EXCHANGE committed but the
# post-swap RENAME did not, and prints the one command that completes it. Signals are the same ones rollback.sh keys on:
# the `traces` engine and its end_time nullability (original = Nullable; successor = non-Nullable; wrapped = Distributed).
assert_pre_exchange_topology() {
    local engine end_time
    engine="$(traces_engine traces)"
    end_time="$(traces_endtime_type traces)"
    [[ -n "$engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }

    if [[ "$engine" == "Distributed" ]]; then
        echo "ERROR: 'traces' is already a Distributed wrapper — the cutover and the wrap already ran. Nothing to EXCHANGE. To roll back, use rollback.sh --stage C." >&2
        exit 1
    fi
    if [[ "$end_time" != Nullable* ]]; then
        # traces already holds the successor schema, so the EXCHANGE has run.
        if [[ -n "$(traces_engine traces_local_v2)" ]]; then
            echo "ERROR: the EXCHANGE already ran (traces holds the successor schema) but 'traces_local_v2' still exists —" >&2
            echo "       the post-swap RENAME did not complete. Finish it, then continue (e.g. --wrap-only or rollback.sh):" >&2
            echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
        else
            echo "ERROR: the EXCHANGE already ran (traces is the successor; old data parked as traces_pre_cutover_backup)." >&2
            echo "       Do NOT re-run it — a second EXCHANGE would swap the tables back. Apply the deferred wrap with --wrap-only, or roll back with rollback.sh --stage B." >&2
        fi
        exit 1
    fi
    [[ -n "$(traces_engine traces_local_v2)" ]] || { echo "ERROR: successor 'traces_local_v2' not found; run the backfill + delta first." >&2; exit 1; }
}

# --wrap-only precondition: traces must be the post-EXCHANGE successor MergeTree (not the original, not already wrapped),
# AND the post-swap RENAME must have completed. In the split state (EXCHANGE done, RENAME not) `traces` already holds
# the successor schema but `traces_local_v2` still holds the old data — wrapping then would orphan the old data under
# the wrong name (finalize.sh would misread it as the disposable successor). So refuse until the rename is finished.
assert_pre_wrap_topology() {
    local engine end_time
    engine="$(traces_engine traces)"
    end_time="$(traces_endtime_type traces)"
    [[ -n "$engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }
    if [[ "$engine" == "Distributed" ]]; then
        echo "ERROR: --wrap-only: 'traces' is already a Distributed wrapper (the wrap already ran). Nothing to do." >&2
        exit 1
    fi
    [[ "$end_time" != Nullable* ]] || {
        echo "ERROR: --wrap-only expects the post-EXCHANGE state (traces = successor schema), but traces has Nullable end_time (the EXCHANGE has not run). Run without --wrap-only first." >&2
        exit 1
    }
    if [[ -n "$(traces_engine traces_local_v2)" ]]; then
        echo "ERROR: --wrap-only: 'traces_local_v2' still exists — the post-EXCHANGE RENAME did not complete, so wrapping" >&2
        echo "       now would orphan the old data under the wrong name. Finish the rename first, then re-run --wrap-only:" >&2
        echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
        exit 1
    fi
    if [[ -z "$(traces_engine traces_pre_cutover_backup)" ]]; then
        echo "ERROR: --wrap-only: 'traces_pre_cutover_backup' (the parked original) does not exist, so there is no path back" >&2
        echo "       to the pre-cutover table if the wrap exposes a problem in the successor. Refusing by default." >&2
        echo "       (Did finalize.sh already drop the backup?) The wrap itself stays reversible without the backup, via" >&2
        echo "       'rollback.sh --unwrap-only'; what is unavailable here is the stage B/C route back to the original. To" >&2
        echo "       wrap a finalized estate, treat lifting this guard as its own reviewed change." >&2
        exit 1
    fi
}

# Pre-EXCHANGE gate: the swap is metadata-only and near-instant, but each replica reads its own local parts afterwards,
# so a replica still fetching backfilled parts (replication_queue) or still applying the deletion-replay mutation
# (system.mutations) would serve an incomplete table. Both are read across every replica via clusterAllReplicas, so one
# connection sees the whole cluster. Aborts unless --force.
#
# The two signals are judged differently, because only one of them is quiet on a healthy cluster:
#
#   * The deletion-replay MUTATION is one bounded statement. It must reach is_done on every replica within
#     --settle-timeout or the gate fails: an unapplied mask means bridged deletes leak live across the swap.
#   * The replication QUEUE is expected to be busy under live ingestion — a GET_PART entry exists for every part a
#     replica has not yet fetched — so requiring an instantaneous 0 would abort on ordinary churn and push the operator
#     toward --force, which the Go/No-Go forbids. It passes as soon as the queue drains; failing that, the verdict is
#     stuck-ness rather than depth: an entry aged past SETTLE_STUCK_AGE_SECONDS, more retries than
#     SETTLE_STUCK_NUM_TRIES, or any last_exception means a replica is genuinely lagging, and the gate fails naming the
#     offending entries per replica.
assert_replication_settled() {
    local cluster deadline polls poll row
    local sample_sql queue_detail_sql mutation_detail_sql
    local queue age tries failures mutations mut_age mut_failed

    # The macro is resolved here only to fail fast with a clear message and to name the cluster in what follows; the
    # blocks below reach it through '{cluster}' themselves.
    cluster="$(ch "SELECT getMacro('cluster')")"
    [[ -n "$cluster" ]] || { echo "ERROR: could not resolve the '{cluster}' macro (getMacro('cluster') was empty). Pass --force only if you have confirmed replication settlement out of band." >&2; exit 1; }

    # All three blocks are rendered up front: a malformed marker then aborts before the first poll rather than midway
    # through a failure report, and the loop reuses one rendered string instead of rebuilding it every pass.
    # Each token identifies exactly one block in 000003, so a marker wrapped around the wrong statement is caught:
    # clusterAllReplicas or system.mutations would match two or three of them and prove nothing.
    sample_sql="$(render settle-sample "$SQL_FILE" "CROSS JOIN")" || exit 2
    queue_detail_sql="$(render settle-queue-detail "$SQL_FILE" postpone_reason)" || exit 2
    mutation_detail_sql="$(render settle-mutation-detail "$SQL_FILE" latest_failed_part)" || exit 2

    # Bounded two ways on purpose: the loop header caps the iteration count, so the gate cannot spin whatever the clock
    # does, and the deadline check keeps a slow-reading cluster from overrunning the seconds --settle-timeout promises.
    # --settle-timeout 0 collapses this to a single sample. The last iteration always breaks (poll == polls fails the
    # guard), so the verdict below always runs against a sample that was actually read — which holds because the
    # validated --settle-timeout bound keeps polls in [1, SETTLE_TIMEOUT_MAX / SETTLE_POLL_SECONDS + 1].
    polls=$(( SETTLE_TIMEOUT / SETTLE_POLL_SECONDS + 1 ))
    deadline=$(( SECONDS + SETTLE_TIMEOUT ))
    for (( poll = 1; poll <= polls; poll++ )); do
        row="$(ch "$sample_sql")" || row=""
        [[ -n "$row" ]] || { echo "ERROR: the settle gate could not read system.replication_queue / system.mutations across cluster '$cluster'. Grant SELECT ON system.* plus REMOTE and CLUSTER, or confirm settlement out of band and pass --force." >&2; exit 1; }
        read -r queue age tries failures mutations mut_age mut_failed <<<"$row"

        if (( queue == 0 && mutations == 0 )); then
            echo "Replication settled across cluster '$cluster': queue drained, deletion-replay mutation done on every replica."
            return 0
        fi
        (( poll < polls && SECONDS < deadline )) || break
        echo "  settling: replication_queue=$queue (oldest ${age}s, max num_tries=$tries, with last_exception=$failures)," \
             "unfinished mutations=$mutations — polling up to ${SETTLE_TIMEOUT}s..."
        sleep "$SETTLE_POLL_SECONDS"
    done

    # Out of time and still not clean. The mutation is unconditional; the queue is judged on stuck-ness, not depth.
    if (( mutations != 0 )); then
        echo "ERROR: the deletion-replay mutation on 'traces_local_v2' has not finished on every replica after ${SETTLE_TIMEOUT}s" >&2
        echo "       ($mutations unfinished, oldest ${mut_age}s, $mut_failed carrying a latest_fail_reason). Swapping now would" >&2
        echo "       serve a replica where the delete mask is not applied, so bridged deletes would leak live across the swap." >&2
        echo "       Unfinished mutations:" >&2
        ch_vertical "$mutation_detail_sql" >&2 || true
        echo "       Let it finish (or fix the cause), then re-run. Raise --settle-timeout for a slow-but-progressing" >&2
        echo "       cluster; --force skips the gate entirely and the runbook's Go/No-Go forbids it in production." >&2
        exit 1
    fi

    if (( age > SETTLE_STUCK_AGE_SECONDS || tries > SETTLE_STUCK_NUM_TRIES || failures > 0 )); then
        echo "ERROR: a replica is lagging, not merely busy — after ${SETTLE_TIMEOUT}s the replication queue for" >&2
        echo "       traces / traces_local_v2 still holds $queue entries: oldest ${age}s (stuck above ${SETTLE_STUCK_AGE_SECONDS}s)," >&2
        echo "       max num_tries=$tries (stuck above ${SETTLE_STUCK_NUM_TRIES}), $failures carrying a last_exception." >&2
        echo "       A behind replica would swap in an incomplete table. Oldest / most-retried entries:" >&2
        ch_vertical "$queue_detail_sql" >&2 || true
        echo "       Resolve the lag, then re-run. Raise --settle-timeout for a slow-but-progressing cluster; --force" >&2
        echo "       skips the gate entirely and the runbook's Go/No-Go forbids it in production." >&2
        exit 1
    fi

    # "Not stuck" rather than "moving": this is a snapshot predicate over the last sample read, and no path here
    # compares consecutive samples. Polling only gives the queue time to drain (the queue == 0 exit above) or a stuck
    # entry time to age past the thresholds, so a shorter --settle-timeout is a weaker gate by exactly that much.
    echo "Replication settled enough across cluster '$cluster': the deletion-replay mutation is done on every replica and"
    echo "no queue entry is stuck — $queue entries, oldest ${age}s, max num_tries=$tries, none with a last_exception."
    echo "That is ordinary ingest churn on a live table."
}

# Topology precondition first (independent of --force, which only bypasses the replication-settle gate).
if [[ "$WRAP_ONLY" == "1" ]]; then
    assert_pre_wrap_topology
else
    assert_pre_exchange_topology
fi

# Timed, because this driver's run is the second half of the final-delta -> EXCHANGE gap and the settle gate is the
# part of it that varies: on a busy cluster it polls up to --settle-timeout, so the delta replay's wall time on its own
# understates the gap by that much.
SETTLE_SECONDS=0
if [[ "$FORCE" == "1" ]]; then
    echo "WARNING: --force set; skipping the replication-settle gate."
else
    SETTLE_STARTED_AT=$SECONDS
    assert_replication_settled
    SETTLE_SECONDS=$(( SECONDS - SETTLE_STARTED_AT ))
fi

run_block() {
    local sql
    case "$1" in
        exchange) sql="$(render exchange "$SQL_FILE" "EXCHANGE TABLES")" || exit 2 ;;
        wrap)     sql="$(render wrap     "$SQL_FILE" "Distributed")"     || exit 2 ;;
        *)        echo "ERROR: unknown block '$1'." >&2; exit 2 ;;
    esac
    # Each ON CLUSTER DDL in the block emits one row per host (host, port, status, error, hosts_remaining,
    # hosts_active); status 0 with an empty error means that host applied it. Labelled so the rows are not mistaken for
    # output of the preceding step (e.g. the final deletion replay).
    echo "  $1: ON CLUSTER responses per host (host, port, status, error, hosts_remaining, hosts_active):"
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
}

# Final deletion replay before the EXCHANGE. delta_replay.sh (step 2) replayed deletes only up to when it ran; cutover_start
# is captured HERE, so a delete bridged in that final gap would be covered by neither the forward replay nor the rollback
# reverse-replay (which starts at cutover_start) and would leak live across the swap. Re-running the deletion-replay block
# (from the single-source 000002) right after capturing cutover_start extends forward coverage to it — the arm is
# idempotent and user-scale (retention off), so it is cheap. Deletions only: the writes in that gap, and those that land
# in the old table during the cross-node EXCHANGE skew, are the open tail write-gap (OPIK-8238), not this step's job.
run_final_deletion_replay() {
    local sql
    # A silent no-op here is the worst failure in this script: the deletes it masks are covered by neither the forward
    # replay (bounded by when delta_replay.sh ran) nor the rollback reverse-replay (bounded by cutover_start), and
    # verify.sh has already run, so nothing downstream would notice them going live again on `traces` after the swap.
    # Hence `render`, which refuses an empty, mis-marked or still-templated block rather than sending it.
    sql="$(render deletion-replay "$DELTA_SQL_FILE" "DELETE FROM")" || exit 2
    # Retag so this run is separable from delta_replay.sh's in query_log. The block sets log_comment in its own trailing
    # SETTINGS, and a per-statement value beats the client's --log_comment, so substituting the value is the only way to
    # distinguish them; the runbook asks for this replay's wall time specifically. Asserted, because a rename of the tag
    # in 000002 would otherwise leave both runs sharing one tag again, silently.
    local from="traces_local_v2_cutover:deletion_replay"
    local to="traces_local_v2_cutover:exchange_and_wrap:final_deletion_replay"
    grep -qF "$from" <<<"$sql" || {
        echo "ERROR: the deletion-replay block no longer sets log_comment = '$from', so this run cannot be" >&2
        echo "       separated from delta_replay.sh's in query_log. Update the tag here to match $DELTA_SQL_FILE." >&2
        exit 2
    }
    sql="${sql//"$from"/"$to"}"
    # --time prints the statement's elapsed seconds to stderr (a bare --query prints nothing). This replay sits inside
    # the final-delta -> EXCHANGE gap where tail writes are left behind, so its wall time is the number to record.
    clickhouse-client "${CH_ARGS[@]}" --time --multiquery --query "$sql"
}

if [[ "$WRAP_ONLY" == "1" ]]; then
    # Deferred second half: the EXCHANGE already happened in a prior --skip-wrap run, so `traces` is the live
    # partitioned data. Do not re-EXCHANGE (that would swap the parked original back in) and do not capture a new
    # cutover_start (the data cutover is already done). Just apply the Distributed wrap.
    #
    # Gapless per node — the wrapper is built under a temp name and one atomic RENAME rotates it in — but the cross-node
    # ON CLUSTER skew remains, which is what --confirm-maintenance (enforced up front, for both wrap paths) covers.
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id). (EXCHANGE was a prior step.)"
    exit 0
fi

# Captured in UTC; the reverse replay and its postcondition parse it as UTC (000004_rollback_reverse_replay.sql).
CUTOVER_START="$(clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:exchange_and_wrap' --query "SELECT toString(now64(6, 'UTC'))")"
echo "RECORD cutover_start=$CUTOVER_START UTC  (if you roll back after this point, pass it with the marker:"
echo "       rollback.sh --cutover-start '$CUTOVER_START UTC')"

echo "Final deletion replay: masking deletes bridged since the last delta_replay so none leak across the swap..."
run_final_deletion_replay

run_block exchange
# Stamped here, not in the tail summary below: the tail ends at the EXCHANGE, so an optional wrap running in between
# must not be counted into it.
EXCHANGE_SECONDS=$SECONDS
echo "EXCHANGE done: 'traces' is now the partitioned data; the old data is parked as 'traces_pre_cutover_backup'."

if [[ "$WITH_WRAP" == "1" ]]; then
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id)."
else
    echo "Distributed wrap deferred (default). Deletes still work on the MergeTree 'traces'. Apply the wrap later with"
    echo "'--wrap-only --confirm-maintenance --confirm-daos-retargeted' once tracesDistributedWrapEnabled=true is live."
fi

echo
echo "TAIL WRITE-GAP: traces written between the last delta and this swap — and any routed at a not-yet-swapped node"
echo "during it — are in traces_pre_cutover_backup, NOT in live traces. Nothing in this procedure carries them across"
echo "yet (OPIK-8238)."
echo "  Length: ${EXCHANGE_SECONDS}s from this driver's start through the EXCHANGE, of which ${SETTLE_SECONDS}s was the"
echo "  settle gate. Add the final delta_replay's replay time (its --time output) for the whole gap."
echo "  Size it now with the post-EXCHANGE compare and --drill-down. TWO of its three key shapes are gap rows: keys"
echo "  shown backup-only (created in the tail), and keys on BOTH sides whose hashes differ with the newer"
echo "  last_updated_at in the backup (updated in the tail — sizing by key presence alone misses these). Differing"
echo "  hashes whose newer version is live are ordinary post-swap writes. The drill-down prints hashes, not versions,"
echo "  so compare last_updated_at per key. Then accept the gap, or recover from the backup BEFORE finalize.sh retires it."
echo "Then verify, and keep traces_pre_cutover_backup for the soak. No ingestion-side config was changed, so there is"
echo "nothing to restore."
