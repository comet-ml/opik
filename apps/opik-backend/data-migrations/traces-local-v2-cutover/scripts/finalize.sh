#!/usr/bin/env bash
#
# Finalizes the cutover once the parked backup has soaked and the live `traces` is confirmed healthy (runbook:
# ../README.md). This is the ONLY script that discards a data-bearing backup, so it is guarded and defaults to a dry run.
#
# The parked backup's NAME depends on how the estate got here, and the two never co-exist; the finalize ACTION differs:
#   * after a successful cutover -> the old original is parked as `traces_pre_cutover_backup` (the live successor is
#                                   `traces`, or `traces_local` behind the Distributed wrapper). DROP it to commit to the
#                                   new layout.
#   * after a rollback           -> the abandoned successor is parked as `traces_post_rollback_backup` (the original is
#                                   live as `traces`). That table IS the migration-000101 `traces_local_v2` object,
#                                   renamed (a replica path is fixed at CREATE and survives renames). RECYCLE it into an
#                                   EMPTY `traces_local_v2` (TRUNCATE + RENAME): discards the successor data but restores
#                                   the exact 000101 shadow — schema, codecs (000106/000107) and replica path — so the
#                                   estate matches the applied Liquibase state and a retry starts from a clean shadow.
# Both are `*_backup` names — retained until this script runs; the working `traces_local_v2` shadow is never detected as a
# backup. This detects whichever parked table is present and never touches the live `traces` / `traces_local` shard.
# Detection is CLUSTER-WIDE (via clusterAllReplicas, like exchange_and_wrap.sh's settle gate): because finalize is the one
# irreversible step and production is multi-replica, a name present on only SOME replicas means an ON CLUSTER DDL has not
# finished propagating, so acting on the connected node's partial view could recycle/drop mid-transition — it refuses
# loudly instead. It also refuses if the live `traces` is empty ACROSS THE CLUSTER (max rows over replicas) while the
# backup is not (the live table may be unhealthy and the "backup" the only copy), if BOTH parked names exist (an
# ambiguous state a human must resolve), and — before a
# recycle — if `traces_local_v2` already exists (recycle renames the backup INTO that name; a stray shadow means a retry
# cutover started before the rollback was finalized).
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
#   --receive-timeout N       seconds tolerated between server packets (receive_timeout), default 1800 against
#                             ClickHouse's own 300. In this driver it also sets distributed_ddl_task_timeout, which is
#                             the binding limit -- see the CH_ARGS comment below, and ../README.md for the trade-off.
#   --confirm         actually run the drop/recycle; without it, prints what would happen and exits (dry run).

set -euo pipefail

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
CONFIRM=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm) CONFIRM=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the drop/exists SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }

# One place for the connection and client-side options, so every call site below carries the same host, port,
# database, log_comment and receive_timeout, and cannot drift from the others.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
# distributed_ddl_task_timeout as well as receive_timeout, and here it is the binding one: this driver runs no long
# SELECT, and its DROP/TRUNCATE are ON CLUSTER, whose wait is capped server-side at 180s by default with
# distributed_ddl_output_mode = 'throw'. A DROP ... SYNC that outlives the cap raises TIMEOUT_EXCEEDED however high the
# client timeout is, while the DDL keeps running in the background — and on the recycle path that aborts between the
# TRUNCATE and the RENAME.
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT" \
          --distributed_ddl_task_timeout="$RECEIVE_TIMEOUT" --log_comment 'traces_local_v2_cutover:finalize')

ch() {
    clickhouse-client "${CH_ARGS[@]}" --query "$1"
}

# Cluster-wide detection. finalize is the one irreversible step and production is multi-replica, so a table's presence is
# resolved across ALL replicas (clusterAllReplicas, mirroring exchange_and_wrap.sh's settle gate), not just the connected
# node. Resolve the cluster and its replica count once; a down replica makes clusterAllReplicas throw — correct here,
# since finalizing against an estate we cannot fully see would be unsafe.
CLUSTER="$(ch "SELECT getMacro('cluster')")"
[[ -n "$CLUSTER" ]] || { echo "ERROR: could not resolve the '{cluster}' macro (getMacro('cluster') was empty)." >&2; exit 1; }
REPLICAS="$(ch "SELECT count() FROM clusterAllReplicas('$CLUSTER', system.one)")"

# Classify a table across the cluster: sets CLUSTER_HAS=1 if present on ALL replicas, 0 if on none, and refuses loudly on
# a mixed (present on some) state — an unfinished ON CLUSTER propagation the connected-node view would hide. Call it
# directly (NOT in "$(...)"), so its refuse-exit stops the whole script rather than only a subshell.
CLUSTER_HAS=0
classify() {
    local n
    n="$(ch "SELECT count() FROM clusterAllReplicas('$CLUSTER', system.tables) WHERE database = '$DATABASE' AND name = '$1'")"
    if [[ "$n" == "0" ]]; then
        CLUSTER_HAS=0
    elif [[ "$n" == "$REPLICAS" ]]; then
        CLUSTER_HAS=1
    else
        echo "ERROR: '$1' exists on $n of $REPLICAS replicas — an ON CLUSTER DDL has not finished propagating." >&2
        echo "       Refusing to finalize a mid-transition cluster; let it settle (or fix the unfinished host), then re-run." >&2
        exit 1
    fi
}

# Row counts as the MAX across replicas (per-host via clusterAllReplicas), so the emptiness guard below reflects the whole
# cluster, not just the connected node — consistent with classify. A Replicated table returns a full copy per replica, so
# group by host and take the most-caught-up one; the post-cutover DROP path's Distributed `traces` already aggregates the
# cluster and this still yields its true total. Fail-loud on a down replica, like classify.
max_rows() {
    ch "SELECT max(c) FROM (SELECT count() AS c FROM clusterAllReplicas('$CLUSTER', $DATABASE.$1) GROUP BY hostName())"
}

classify traces
[[ "$CLUSTER_HAS" == "1" ]] || { echo "ERROR: live 'traces' table not found on all replicas in '$DATABASE'." >&2; exit 1; }

# Detect the parked backup by name: traces_pre_cutover_backup (post-successful-cutover) or traces_post_rollback_backup
# (post-rollback). They never co-exist in a clean flow; if both are present the estate is ambiguous — refuse.
classify traces_pre_cutover_backup;   HAS_PRECUTOVER="$CLUSTER_HAS"
classify traces_post_rollback_backup; HAS_POST_ROLLBACK="$CLUSTER_HAS"

if [[ "$HAS_PRECUTOVER" == "1" && "$HAS_POST_ROLLBACK" == "1" ]]; then
    echo "ERROR: both 'traces_pre_cutover_backup' and 'traces_post_rollback_backup' exist — ambiguous state." >&2
    echo "       Expected exactly one parked backup. Investigate and drop the correct one by hand." >&2
    exit 1
elif [[ "$HAS_PRECUTOVER" == "1" ]]; then
    BACKUP="traces_pre_cutover_backup"
elif [[ "$HAS_POST_ROLLBACK" == "1" ]]; then
    BACKUP="traces_post_rollback_backup"
else
    echo "Nothing to finalize: no parked backup ('traces_pre_cutover_backup' or 'traces_post_rollback_backup') exists."
    exit 0
fi

LIVE_ROWS="$(max_rows traces)"
BACKUP_ROWS="$(max_rows "$BACKUP")"

# Refuse the dangerous case: a live table that looks empty across the cluster while the backup holds data.
if [[ "$LIVE_ROWS" == "0" && "$BACKUP_ROWS" != "0" ]]; then
    echo "ERROR: live 'traces' is empty but '$BACKUP' has $BACKUP_ROWS rows. Refusing to drop the backup —" >&2
    echo "       verify the live table is the healthy one before finalizing." >&2
    exit 1
fi

echo "Live 'traces': $LIVE_ROWS rows. Parked '$BACKUP': $BACKUP_ROWS rows."

# max_table_size_to_drop = 0 disables the drop-size guard (default 50 GB): the parked backup is a full copy (the old
# original after a cutover, or the successor after a rollback) and multi-TB in production, so without the override the
# DROP / TRUNCATE throws "size exceeds the limit".
if [[ "$BACKUP" == "traces_post_rollback_backup" ]]; then
    # Rollback finalize: recycle the parked successor — physically the 000101 `traces_local_v2` object (its replica path
    # is fixed at CREATE and unchanged by the rename) — back into an empty `traces_local_v2`. ClickHouse has no single
    # truncate-and-rename, so this is two statements, each atomic PER HOST and ON CLUSTER; ordered TRUNCATE-then-RENAME so
    # the only state a crash between them can leave is an empty `traces_post_rollback_backup` — which re-running finalize
    # recovers on a single host, and under partial ON CLUSTER propagation (applied on some replicas, not all)
    # detects-and-refuses (cluster-wide `classify` sees a mixed state — finish the RENAME by hand, then re-run), never
    # silently corrupting (RENAME-first could strand a populated `traces_local_v2` that a retry backfill would mis-skip). ACROSS the
    # shard's replicas ON CLUSTER runs synchronously (the client blocks until every reachable replica applies it, or throws
    # naming a laggard that then converges via the DDL queue), NOT globally atomic. Both statements touch only the parked
    # backup / disposable shadow — never the live `traces` — so unlike the rollback promote and the wrap (which rename live
    # `traces`) the brief cross-replica skew is invisible to readers, and finalize needs no maintenance window.
    #
    # Guard the destination first: recycle renames the backup INTO `traces_local_v2`, and ClickHouse RENAME fails on an
    # existing target. A stray `traces_local_v2` here means a retry cutover started before this rollback was finalized —
    # refuse (cluster-wide) BEFORE truncating, so we fail early with a clear message instead of after the TRUNCATE.
    classify traces_local_v2
    if [[ "$CLUSTER_HAS" != "0" ]]; then
        echo "ERROR: 'traces_local_v2' already exists — cannot recycle '$BACKUP' into it (RENAME will not overwrite)." >&2
        echo "       This usually means a retry cutover began before the rollback was finalized. Resolve the estate" >&2
        echo "       (inspect/drop 'traces_local_v2') before recycling." >&2
        exit 1
    fi
    if [[ "$CONFIRM" != "1" ]]; then
        echo "DRY RUN: would recycle $DATABASE.$BACKUP into an empty $DATABASE.traces_local_v2 (TRUNCATE + RENAME)."
        echo "         Re-run with --confirm."
        exit 0
    fi
    ch "TRUNCATE TABLE $BACKUP ON CLUSTER '{cluster}' SETTINGS max_table_size_to_drop = 0"
    ch "RENAME TABLE $BACKUP TO traces_local_v2 ON CLUSTER '{cluster}'"
    echo "Recycled $DATABASE.$BACKUP into an empty $DATABASE.traces_local_v2. The rollback is finalized."
else
    if [[ "$CONFIRM" != "1" ]]; then
        echo "DRY RUN: would DROP TABLE $DATABASE.$BACKUP. Re-run with --confirm to drop it."
        exit 0
    fi
    ch "DROP TABLE IF EXISTS $BACKUP ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0"
    echo "Dropped $DATABASE.$BACKUP. The cutover is finalized."
fi
