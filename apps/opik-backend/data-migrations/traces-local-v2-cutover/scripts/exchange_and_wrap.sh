#!/usr/bin/env bash
#
# Driver for step 3 of the buffered traces cutover: EXCHANGE + Distributed wrap (runbook: ../README.md).
#
# Captures and prints cutover_start (needed by rollback.sh if you roll back after this), then runs the `exchange` block
# of db-app-analytics/000003_exchange_and_wrap.sql. By default it stops there (EXCHANGE only) — the Distributed `wrap`
# block runs only with --with-wrap. Run it right after the delta + replay + verify, while the async-insert buffer is
# still holding writes.
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
#   --backfill-start TS  the anchor printed by backfill.sh. REQUIRED for every EXCHANGE path (not --wrap-only): just
#                     before the swap this runs a final deletion replay from that anchor, so deletes bridged since the
#                     last delta_replay.sh don't leak live across the EXCHANGE (they'd be covered by neither the forward
#                     replay nor the rollback reverse-replay otherwise).
#   (default)         run ONLY the EXCHANGE (the data cutover), then stop — leaves `traces` a MergeTree where deletes
#                     still work. The Distributed wrap is deferred (see above).
#   --with-wrap       also apply the Distributed wrap in the same run (EXCHANGE + wrap). Use only once
#                     tracesDistributedWrapEnabled=true (OPIK-7455) is live so trace mutations target `traces_local`.
#                     Mutually exclusive with --skip-wrap / --wrap-only.
#   --skip-wrap       explicit alias for the default (EXCHANGE only); accepted for clarity and back-compat.
#   --wrap-only       run ONLY the Distributed wrap on the already-swapped `traces` (no EXCHANGE, no new cutover_start)
#                     — the deferred second half of a prior EXCHANGE-only run. Mutually exclusive with the above.
#   --force           skip the replication-settle gate. By default the swap aborts while any replica still
#                     has replication-queue backlog or an unfinished mutation on traces / traces_local_v2, since a
#                     behind replica would swap in an incomplete table. Use only if settlement is confirmed out of band.
#   --confirm-maintenance  REQUIRED with --wrap-only. The wrap is gapless per node (atomic rotate), but a brief cross-node
#                     ON CLUSTER propagation skew remains, during which a Distributed query can hit a not-yet-created
#                     `traces_local` on a lagging node and fail. That is a READ exposure as much as a write one, so
#                     re-raising the async-insert buffer does NOT by itself discharge this flag — the buffer parks
#                     writes and does nothing for reads. Unlike the same-run --with-wrap path (still buffered from the
#                     EXCHANGE), --wrap-only runs later against live traffic. Assert that traffic is quiesced or a
#                     maintenance window is in effect. Mirrored by rollback.sh --unwrap-only, which reverses this.
#   --confirm-daos-retargeted  REQUIRED whenever the wrap is applied (--with-wrap or --wrap-only). Asserts the trace
#                     delete/mutation DAOs already target `traces_local`: set backend config
#                     databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true (OPIK-7455) in lockstep with the wrap.
#                     A Distributed `traces` rejects mutations, so without the flag delete-by-id and retention deletes
#                     return 500 the moment the wrap lands. The script cannot inspect backend config, so the operator
#                     must assert it.
#   --confirm-buffer-raised  REQUIRED for every EXCHANGE path (the default and --with-wrap; not --wrap-only). Asserts the
#                     async-insert buffer (asyncInsertBusyTimeoutMaxMs) is raised on every backend instance — it holds
#                     writes across the swap so they land on the new table; at the default, a write in the final window
#                     can commit to the old table and be lost after the EXCHANGE. It's a backend setting the script can't
#                     read, so the operator must assert it.
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
BACKFILL_START=""
SKIP_WRAP=0
WITH_WRAP=0
WRAP_ONLY=0
FORCE=0
CONFIRM_MAINTENANCE=0
CONFIRM_DAOS_RETARGETED=0
CONFIRM_BUFFER_RAISED=0
CONFIRM_RETENTION_PAUSED=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --skip-wrap) SKIP_WRAP=1; shift ;;
        --with-wrap) WITH_WRAP=1; shift ;;
        --wrap-only) WRAP_ONLY=1; shift ;;
        --force) FORCE=1; shift ;;
        --confirm-maintenance) CONFIRM_MAINTENANCE=1; shift ;;
        --confirm-daos-retargeted) CONFIRM_DAOS_RETARGETED=1; shift ;;
        --confirm-buffer-raised) CONFIRM_BUFFER_RAISED=1; shift ;;
        --confirm-retention-paused) CONFIRM_RETENTION_PAUSED=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }
[[ -f "$DELTA_SQL_FILE" ]] || { echo "ERROR: cannot find $DELTA_SQL_FILE" >&2; exit 2; }
# --backfill-start (the anchor printed by backfill.sh) is interpolated into the final deletion replay; validate its shape.
[[ -z "$BACKFILL_START" || "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
# At most one wrap mode. Default (none set) is EXCHANGE only.
if (( SKIP_WRAP + WITH_WRAP + WRAP_ONLY > 1 )); then
    echo "ERROR: --skip-wrap, --with-wrap and --wrap-only are mutually exclusive" >&2; exit 2
fi
# The deferred wrap is gapless per node but has a brief cross-node ON CLUSTER propagation skew, and --wrap-only runs
# against live, unbuffered ingestion — unlike the same-run --with-wrap path, still buffered from the EXCHANGE. Refuse
# (fail fast, before touching ClickHouse) unless the operator asserts the buffer is re-raised / ingestion quiesced / a
# maintenance window is in effect.
if [[ "$WRAP_ONLY" == "1" && "$CONFIRM_MAINTENANCE" != "1" ]]; then
    echo "ERROR: --wrap-only requires --confirm-maintenance. The wrap has a brief cross-node ON CLUSTER window in which a" >&2
    echo "       Distributed query can reach a node where 'traces_local' does not exist yet and fail — a READ exposure as" >&2
    echo "       much as a write one, so re-raising asyncInsertBusyTimeoutMaxMs is not sufficient on its own (it parks" >&2
    echo "       writes and does nothing for reads). Quiesce traffic or take a maintenance window, then re-run with it." >&2
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
# The EXCHANGE is the zero-loss step: writes in the final-delta -> EXCHANGE gap must be held by the raised async-insert
# buffer (asyncInsertBusyTimeoutMaxMs) so they flush onto the new table after the swap; if the buffer is at its default,
# such a write can commit to the old table just before the swap and be silently lost. The buffer is a backend per-query
# setting the script can't read, so require the operator to assert it. Applies to every EXCHANGE path (not --wrap-only,
# which does no EXCHANGE). Fail fast, before touching ClickHouse.
if [[ "$WRAP_ONLY" != "1" && "$CONFIRM_BUFFER_RAISED" != "1" ]]; then
    echo "ERROR: the EXCHANGE requires --confirm-buffer-raised. Raise databaseAnalytics.asyncInsertBusyTimeoutMaxMs on" >&2
    echo "       every backend instance first — it holds writes across the swap; at the default, writes in the final" >&2
    echo "       window can commit to the old table and be lost after the EXCHANGE — then re-run with the flag." >&2
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
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --log_comment 'traces_local_v2_cutover:exchange_and_wrap' --query "$1"
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

# Pre-EXCHANGE gate: the swap is metadata-only and near-instant, but each replica reads its own local parts afterwards.
# If a replica is still fetching backfilled parts (replication_queue) or has not finished the deletion-replay mutation
# (system.mutations), swapping now would make that replica serve an incomplete table. Both are checked across ALL
# replicas via clusterAllReplicas, so a single connection sees the whole cluster's backlog. Aborts unless --force.
assert_replication_settled() {
    local cluster queue mutations
    cluster="$(ch "SELECT getMacro('cluster')")"
    [[ -n "$cluster" ]] || { echo "ERROR: could not resolve the '{cluster}' macro (getMacro('cluster') was empty). Pass --force only if you have confirmed replication settlement out of band." >&2; exit 1; }

    queue="$(ch "SELECT count()
        FROM clusterAllReplicas('$cluster', system.replication_queue)
        WHERE database = '$DATABASE'
          AND table IN ('traces', 'traces_local_v2')")"
    mutations="$(ch "SELECT count()
        FROM clusterAllReplicas('$cluster', system.mutations)
        WHERE database = '$DATABASE'
          AND table = 'traces_local_v2'
          AND is_done = 0")"

    if [[ "$queue" != "0" || "$mutations" != "0" ]]; then
        echo "ERROR: replication not settled across cluster '$cluster' — replication_queue=$queue, unfinished mutations=$mutations." >&2
        echo "       Wait for both to reach 0 (parts fetched, deletion replay applied everywhere) before the EXCHANGE, or pass --force to override." >&2
        exit 1
    fi
    echo "Replication settled across cluster '$cluster' (replication_queue=0, mutations done)."
}

# Topology precondition first (independent of --force, which only bypasses the replication-settle gate).
if [[ "$WRAP_ONLY" == "1" ]]; then
    assert_pre_wrap_topology
else
    assert_pre_exchange_topology
fi

if [[ "$FORCE" == "1" ]]; then
    echo "WARNING: --force set; skipping the replication-settle gate."
else
    assert_replication_settled
fi

# No placeholder may survive into the SQL this driver sends. Both blocks it renders live in files it does not own —
# 000003 here, and the deletion-replay block of 000002, which it shares with delta_replay.sh — so a ${...} added
# there without a matching substitution added HERE reaches the server as a literal and fails mid-cutover. The
# reference SQL's own header keeps a hand-maintained placeholder inventory; a hand-maintained list drifts, so this
# checks the rendered text instead of trusting it. $2 names the block, for diagnostics.
assert_no_placeholder() {
    if grep -qE '\$\{[A-Za-z_][A-Za-z0-9_]*\}' <<<"$1"; then
        echo "ERROR: unsubstituted placeholder(s) survive in the rendered '$2' SQL:" >&2
        grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*\}' <<<"$1" | sort -u | sed 's/^/       /' >&2
        echo "       Refusing to send SQL containing a literal placeholder. Add the missing substitution to this" >&2
        echo "       driver (delta_replay.sh renders the same 000002 block and needs it too)." >&2
        exit 2
    fi
}

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block from the reference SQL (exact-line markers).
extract() {
    awk -v begin="-- >>> BEGIN $1" -v end="-- >>> END $1" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$SQL_FILE"
}

run_block() {
    local sql
    sql="$(extract "$1")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    assert_no_placeholder "$sql" "$1"
    # Each ON CLUSTER DDL in the block emits one row per host (host, port, status, error, hosts_remaining,
    # hosts_active); status 0 with an empty error means that host applied it. Labelled so the rows are not mistaken for
    # output of the preceding step (e.g. the final deletion replay).
    echo "  $1: ON CLUSTER responses per host (host, port, status, error, hosts_remaining, hosts_active):"
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --multiquery --query "$sql"
}

# Final deletion replay before the EXCHANGE. delta_replay.sh (step 2) replayed deletes only up to when it ran; cutover_start
# is captured HERE, so a delete bridged in that final gap would be covered by neither the forward replay nor the rollback
# reverse-replay (which starts at cutover_start) and would leak live across the swap. Re-running the deletion-replay block
# (from the single-source 000002) right after capturing cutover_start extends forward coverage to it — the arm is
# idempotent and user-scale (retention off), so it is cheap. Deletions only: writes in the gap are held by the async
# buffer and flush onto the successor after the swap.
run_final_deletion_replay() {
    local sql
    sql="$(awk -v begin="-- >>> BEGIN deletion-replay" -v end="-- >>> END deletion-replay" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$DELTA_SQL_FILE")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
    assert_no_placeholder "$sql" "deletion-replay"
    # --time prints the statement's elapsed seconds to stderr (a bare --query prints nothing). This replay sits inside
    # the final-delta -> EXCHANGE gap the buffer hold has to cover, so its wall time is the number to record.
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --log_comment 'traces_local_v2_cutover:exchange_and_wrap:final_deletion_replay' --time --multiquery --query "$sql"
}

if [[ "$WRAP_ONLY" == "1" ]]; then
    # Deferred second half: the EXCHANGE already happened in a prior --skip-wrap run, so `traces` is the live
    # partitioned data. Do not re-EXCHANGE (that would swap the parked original back in) and do not capture a new
    # cutover_start (the data cutover is already done). Just apply the Distributed wrap.
    #
    # The wrap is two non-atomic statements (RENAME traces -> traces_local, then CREATE Distributed traces); between them
    # `traces` does not exist, so concurrent INSERT/SELECT fails with "Table traces doesn't exist" (ON CLUSTER widens the
    # window per-node). The same-run path is covered by the still-raised EXCHANGE buffer, but --wrap-only runs later
    # against live, unbuffered ingestion. PRECONDITION: re-raise databaseAnalytics.asyncInsertBusyTimeoutMaxMs (or quiesce
    # ingestion / assert a maintenance window) so the wrap runs under the same buffered conditions as the EXCHANGE.
    # --confirm-maintenance was already enforced up front (gapless per node, but a brief cross-node ON CLUSTER window).
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id). (EXCHANGE was a prior step.)"
    exit 0
fi

CUTOVER_START="$(clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --log_comment 'traces_local_v2_cutover:exchange_and_wrap' --query "SELECT toString(now64(6))")"
echo "RECORD cutover_start=$CUTOVER_START  (pass to rollback.sh --cutover-start if you roll back after this point)"

echo "Final deletion replay: masking deletes bridged since the last delta_replay so none leak across the swap..."
run_final_deletion_replay

run_block exchange
echo "EXCHANGE done: 'traces' is now the partitioned data; the old data is parked as 'traces_pre_cutover_backup'."

if [[ "$WITH_WRAP" == "1" ]]; then
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id)."
else
    echo "Distributed wrap deferred (default). Deletes still work on the MergeTree 'traces'. Apply the wrap later with"
    echo "'--wrap-only --confirm-maintenance --confirm-daos-retargeted' once tracesDistributedWrapEnabled=true is live."
fi

echo "Restore databaseAnalytics.asyncInsertBusyTimeoutMaxMs to default, verify, and keep traces_pre_cutover_backup for the soak."
