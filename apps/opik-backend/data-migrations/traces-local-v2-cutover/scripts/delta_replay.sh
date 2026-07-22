#!/usr/bin/env bash
#
# Driver for step 2 of the buffered traces cutover: delta-insert + deletion replay (runbook: ../README.md).
#
# Reads db-app-analytics/000002_delta_and_deletion_replay.sql (the single source), substitutes the placeholders and runs
# it. Run it after backfill.sh, then verify.sh, then exchange_and_wrap.sh.
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).
#
# Options:
#   --database NAME            analytics database (e.g. opik). Required.
#   --backfill-start TS        the anchor printed by backfill.sh ("RECORD backfill_start=..."). Required.
#   --max-insert-block-size N  SETTINGS max_insert_block_size for the delta INSERT. Default 1048576.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000002_delta_and_deletion_replay.sql"

DATABASE=""
BACKFILL_START=""
MAX_INSERT_BLOCK_SIZE=1048576

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="$2"; shift 2 ;;
        --backfill-start) BACKFILL_START="$2"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --backfill-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start is required (printed by backfill.sh)" >&2; exit 2; }
[[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }

echo "Reminder: raise databaseAnalytics.asyncInsertBusyTimeoutMaxMs before this step (backend config, not SQL) and"
echo "restore it after the EXCHANGE."

sql="$(cat "$SQL_FILE")"
sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
clickhouse-client --database "$DATABASE" --multiquery --query "$sql"

echo "Delta + deletion replay complete. Run verify.sh before the EXCHANGE."
