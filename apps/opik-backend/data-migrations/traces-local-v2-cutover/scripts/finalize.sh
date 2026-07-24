#!/usr/bin/env bash
#
# Drops the parked backup once the cutover (or a rollback) has soaked and the live `traces` is confirmed healthy
# (runbook: ../README.md). This is the ONLY script that drops a data-bearing table, so it is guarded and defaults to a
# dry run.
#
# The parked backup's NAME depends on how the estate got here, and the two never co-exist:
#   * after a successful cutover -> the old original is parked as `traces_pre_cutover_backup` (the live successor is
#                                   `traces`, or `traces_local` behind the Distributed wrapper). Dropping it commits to
#                                   the new layout.
#   * after a rollback           -> the abandoned successor is parked as `traces_local_v2` (the original is live as
#                                   `traces`). Dropping it abandons the migration.
# This detects whichever parked table is present and drops it — it never targets the live `traces` or the live
# `traces_local` shard. It refuses if the live `traces` is empty while the backup is not (the live table may be
# unhealthy and the "backup" the only copy), and if BOTH parked names exist (an ambiguous, unexpected state that a human
# must resolve).
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).
#
# Options:
#   --database NAME   analytics database (e.g. opik). Required.
#   --confirm         actually drop; without it, prints what would be dropped and exits (dry run).

set -euo pipefail

DATABASE=""
CONFIRM=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="$2"; shift 2 ;;
        --confirm) CONFIRM=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the drop/exists SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }

ch() {
    clickhouse-client --database "$DATABASE" --query "$1"
}

exists() {
    ch "SELECT count() FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}

[[ "$(exists traces)" != "0" ]] || { echo "ERROR: live 'traces' table not found in '$DATABASE'." >&2; exit 1; }

# Detect the parked backup by name: traces_pre_cutover_backup (post-successful-cutover) or traces_local_v2
# (post-rollback). They never co-exist in a clean flow; if both are present the estate is ambiguous — refuse.
HAS_PRECUTOVER="$([[ "$(exists traces_pre_cutover_backup)" != "0" ]] && echo 1 || echo 0)"
HAS_V2="$([[ "$(exists traces_local_v2)" != "0" ]] && echo 1 || echo 0)"

if [[ "$HAS_PRECUTOVER" == "1" && "$HAS_V2" == "1" ]]; then
    echo "ERROR: both 'traces_pre_cutover_backup' and 'traces_local_v2' exist — ambiguous state." >&2
    echo "       Expected exactly one parked backup. Investigate and drop the correct one by hand." >&2
    exit 1
elif [[ "$HAS_PRECUTOVER" == "1" ]]; then
    BACKUP="traces_pre_cutover_backup"
elif [[ "$HAS_V2" == "1" ]]; then
    BACKUP="traces_local_v2"
else
    echo "Nothing to finalize: no parked backup ('traces_pre_cutover_backup' or 'traces_local_v2') exists."
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
if [[ "$CONFIRM" != "1" ]]; then
    echo "DRY RUN: would DROP TABLE $DATABASE.$BACKUP. Re-run with --confirm to drop it."
    exit 0
fi

# max_table_size_to_drop = 0 disables the drop-size guard (default 50 GB): the parked backup is the full old original
# (multi-TB after a successful cutover), so without the override the DROP throws "size exceeds the limit".
ch "DROP TABLE IF EXISTS $BACKUP ON CLUSTER '{cluster}' SYNC SETTINGS max_table_size_to_drop = 0"
echo "Dropped $DATABASE.$BACKUP. The cutover is finalized."
