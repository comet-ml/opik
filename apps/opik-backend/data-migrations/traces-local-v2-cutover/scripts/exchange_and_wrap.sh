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
# breaks the product's trace-delete / retention paths until those DAOs target `traces_local`. The safe default is to
# leave `traces` a MergeTree (deletes keep working) and apply the wrap later, once the DAOs are sharding-aware.
#
# Guarded like rollback.sh: it asserts the live `traces` topology matches the requested action before touching anything,
# so a re-run cannot silently swap the tables back, and a partial EXCHANGE (swap done, post-swap RENAME not) is detected
# with the command to finish it.
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).
#
# Options:
#   --database NAME   analytics database (e.g. opik). Required.
#   (default)         run ONLY the EXCHANGE (the data cutover), then stop — leaves `traces` a MergeTree where deletes
#                     still work. The Distributed wrap is deferred (see above).
#   --with-wrap       also apply the Distributed wrap in the same run (EXCHANGE + wrap). Use only once the delete/read
#                     DAOs are sharding-aware. Mutually exclusive with --skip-wrap / --wrap-only.
#   --skip-wrap       explicit alias for the default (EXCHANGE only); accepted for clarity and back-compat.
#   --wrap-only       run ONLY the Distributed wrap on the already-swapped `traces` (no EXCHANGE, no new cutover_start)
#                     — the deferred second half of a prior EXCHANGE-only run. Mutually exclusive with the above.
#   --force           skip the replication-settle gate. By default the swap aborts while any replica still
#                     has replication-queue backlog or an unfinished mutation on traces / traces_local_v2, since a
#                     behind replica would swap in an incomplete table. Use only if settlement is confirmed out of band.
#   --confirm-maintenance  REQUIRED with --wrap-only. The wrap is a non-atomic RENAME->CREATE that briefly makes `traces`
#                     unavailable; unlike the same-run --with-wrap path (still buffered from the EXCHANGE), --wrap-only
#                     runs later against live, unbuffered ingestion. This flag asserts the async-insert buffer is
#                     re-raised (or ingestion quiesced / a maintenance window is in effect); the wrap refuses without it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000003_exchange_and_wrap.sql"

DATABASE=""
SKIP_WRAP=0
WITH_WRAP=0
WRAP_ONLY=0
FORCE=0
CONFIRM_MAINTENANCE=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="$2"; shift 2 ;;
        --skip-wrap) SKIP_WRAP=1; shift ;;
        --with-wrap) WITH_WRAP=1; shift ;;
        --wrap-only) WRAP_ONLY=1; shift ;;
        --force) FORCE=1; shift ;;
        --confirm-maintenance) CONFIRM_MAINTENANCE=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }
# At most one wrap mode. Default (none set) is EXCHANGE only.
if (( SKIP_WRAP + WITH_WRAP + WRAP_ONLY > 1 )); then
    echo "ERROR: --skip-wrap, --with-wrap and --wrap-only are mutually exclusive" >&2; exit 2
fi
# The deferred wrap briefly makes `traces` unavailable (non-atomic RENAME->CREATE) and runs against live, unbuffered
# ingestion — unlike the same-run --with-wrap path, which is still buffered from the EXCHANGE. Refuse (fail fast, before
# touching ClickHouse) unless the operator asserts the buffer is re-raised / ingestion quiesced / a maintenance window.
if [[ "$WRAP_ONLY" == "1" && "$CONFIRM_MAINTENANCE" != "1" ]]; then
    echo "ERROR: --wrap-only requires --confirm-maintenance. Re-raise asyncInsertBusyTimeoutMaxMs (or quiesce ingestion /" >&2
    echo "       take a maintenance window) first — the wrap briefly makes 'traces' unavailable — then re-run with it." >&2
    exit 2
fi

ch() {
    clickhouse-client --database "$DATABASE" --query "$1"
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
            echo "         clickhouse-client --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
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
        echo "         clickhouse-client --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
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

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block from the reference SQL (exact-line markers).
extract() {
    awk -v begin="-- >>> BEGIN $1" -v end="-- >>> END $1" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$SQL_FILE"
}

run_block() {
    local sql
    sql="$(extract "$1")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    clickhouse-client --database "$DATABASE" --multiquery --query "$sql"
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
    # --confirm-maintenance was already enforced up front (the wrap briefly makes `traces` unavailable).
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id). (EXCHANGE was a prior step.)"
    exit 0
fi

CUTOVER_START="$(clickhouse-client --database "$DATABASE" --query "SELECT toString(now64(6))")"
echo "RECORD cutover_start=$CUTOVER_START  (pass to rollback.sh --cutover-start if you roll back after this point)"

run_block exchange
echo "EXCHANGE done: 'traces' is now the partitioned data; the old data is parked as 'traces_pre_cutover_backup'."

if [[ "$WITH_WRAP" == "1" ]]; then
    run_block wrap
    echo "Distributed wrap done: 'traces' fronts 'traces_local' via sipHash64(project_id)."
else
    echo "Distributed wrap deferred (default). Deletes still work on the MergeTree 'traces'. Apply the wrap later with"
    echo "--wrap-only once the delete/read DAOs target traces_local."
fi

echo "Restore databaseAnalytics.asyncInsertBusyTimeoutMaxMs to default, verify, and keep traces_pre_cutover_backup for the soak."
