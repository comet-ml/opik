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
# backup. This detects whichever parked table is present and never touches the live `traces` / `traces_local` shard. It
# refuses if the live `traces` is empty while the backup is not (the live table may be unhealthy and the "backup" the only
# copy), and if BOTH parked names exist (an ambiguous, unexpected state that a human must resolve).
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).
#
# Options:
#   --database NAME   analytics database (e.g. opik). Required.
#   --confirm         actually run the drop/recycle; without it, prints what would happen and exits (dry run).

set -euo pipefail

DATABASE=""
CONFIRM=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm) CONFIRM=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the drop/exists SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }

ch() {
    clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:finalize' --query "$1"
}

exists() {
    ch "SELECT count() FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}

[[ "$(exists traces)" != "0" ]] || { echo "ERROR: live 'traces' table not found in '$DATABASE'." >&2; exit 1; }

# Detect the parked backup by name: traces_pre_cutover_backup (post-successful-cutover) or traces_post_rollback_backup
# (post-rollback). They never co-exist in a clean flow; if both are present the estate is ambiguous — refuse.
HAS_PRECUTOVER="$([[ "$(exists traces_pre_cutover_backup)" != "0" ]] && echo 1 || echo 0)"
HAS_POST_ROLLBACK="$([[ "$(exists traces_post_rollback_backup)" != "0" ]] && echo 1 || echo 0)"

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

LIVE_ROWS="$(ch "SELECT count() FROM traces")"
BACKUP_ROWS="$(ch "SELECT count() FROM $BACKUP")"

# Refuse the dangerous case: a live table that looks empty while the backup holds data.
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
    # the only state a crash between them can leave is an empty `traces_post_rollback_backup`, which re-running finalize
    # recovers (RENAME-first could strand a populated `traces_local_v2` that a retry backfill would mis-skip). ACROSS the
    # shard's replicas ON CLUSTER runs synchronously (the client blocks until every reachable replica applies it, or throws
    # naming a laggard that then converges via the DDL queue), NOT globally atomic. Both statements touch only the parked
    # backup / disposable shadow — never the live `traces` — so unlike the rollback promote and the wrap (which rename live
    # `traces`) the brief cross-replica skew is invisible to readers, and finalize needs no maintenance window.
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
