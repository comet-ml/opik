#!/usr/bin/env bash
#
# Driver for step 2 of the buffered traces cutover: delta-insert + deletion replay (runbook: ../README.md).
#
# Reads db-app-analytics/000002_delta_and_deletion_replay.sql (the single source), substitutes the placeholders and runs
# it. Run it after backfill.sh, then verify.sh, then exchange_and_wrap.sh.
#
# Connection: CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment, plus --host and --port. CLICKHOUSE_PORT is
# NOT honored by clickhouse-client, and CLICKHOUSE_HOST is honored only when no connection flag is given, so pass
# --host and --port together. The user must be able to set `log_comment` (used for cutover attribution in
# query_log): a `readonly = 1` profile rejects it outright ("Cannot modify 'log_comment' setting in readonly mode"),
# so a read-only assessor needs `readonly = 2` and the migration user needs a non-readonly profile.
#
# Options:
#   --database NAME            analytics database (e.g. opik). Required.
#   --port N                  ClickHouse NATIVE port, when it is not the default 9000 — e.g. reaching a cluster through
#                             a port-forward or bastion on a local port. Required because clickhouse-client honors
#                             CLICKHOUSE_HOST / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment but does
#                             NOT honor CLICKHOUSE_PORT, so the port cannot be passed via env.
#   --host HOST               ClickHouse host. Pass it together with --port: clickhouse-client honors CLICKHOUSE_HOST
#                             ONLY when no connection flag is given, so supplying --port alone silently reverts the host
#                             to localhost. User/password still come from CLICKHOUSE_USER / CLICKHOUSE_PASSWORD (keeping
#                             the password out of argv).
#   --backfill-start TS        the anchor printed by backfill.sh ("RECORD backfill_start=..."). Required.
#   --max-insert-block-size N  SETTINGS max_insert_block_size for the delta INSERT. Default 1048576.
#   --max-partitions-per-insert-block N
#                             partitions one insert block of the delta INSERT may span (SETTINGS
#                             max_partitions_per_insert_block). Default 2000; 0 = unlimited. Same correctness gate as in
#                             backfill.sh, and it applies here too: the delta writes into the same weekly-partitioned
#                             shadow, and its `last_updated_at` arm re-copies UPDATES TO OLD ROWS, so a far-future-id row
#                             updated during the window is pulled in and lands in its far-future partition. ClickHouse
#                             defaults this to 100 and aborts the INSERT rather than degrading
#                             (throw_on_max_partitions_per_insert_block = 1). An abort here is worse than in the
#                             backfill: the final delta runs immediately before the EXCHANGE, inside the window the
#                             runbook asks you to keep short. Pass the SAME value used for the backfill.
#   --max-insert-threads N    threads for the delta INSERT SELECT pipeline (SETTINGS max_insert_threads).
#                             OMITTED BY DEFAULT = INHERIT the server's setting (the line is stripped from
#                             the SQL); an explicit 0 FORCES "INSERT SELECT no parallel execution". Same knob, same caveats and same two costs (memory, part
#                             count) as in backfill.sh -- see its option docs for the full diagnosis.
#                             PASS THE SAME VALUE USED FOR THE BACKFILL: the delta writes into the
#                             same table through the same insert path.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000002_delta_and_deletion_replay.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
BACKFILL_START=""
MAX_INSERT_BLOCK_SIZE=1048576
MAX_PARTITIONS_PER_INSERT_BLOCK=2000  # partitions per block for the delta INSERT; see the option docs above. 0 = unlimited.
MAX_INSERT_THREADS=""                 # threads for the delta INSERT SELECT. EMPTY = inherit the server's setting (the
                                      # line is stripped from the SQL). Explicit 0 FORCES no parallel execution.

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --backfill-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start is required (printed by backfill.sh)" >&2; exit 2; }
[[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
# 0 is meaningful (ClickHouse reads it as "unlimited"), so allow it — unlike the bound above. Upper-bounded at 6 digits:
# the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be rendered
# into the SQL and rejected by the server mid-run instead of here.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
[[ -z "$MAX_INSERT_THREADS" || "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (force no parallel INSERT SELECT execution) or 1..99; omit it entirely to inherit the server's setting." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }

echo "Reminder: raise databaseAnalytics.asyncInsertBusyTimeoutMaxMs before this step (backend config, not SQL) and"
echo "restore it after the EXCHANGE."

sql="$(cat "$SQL_FILE")"
sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
sql="${sql//'${MAX_PARTITIONS_PER_INSERT_BLOCK}'/$MAX_PARTITIONS_PER_INSERT_BLOCK}"
if [[ -z "$MAX_INSERT_THREADS" ]]; then
    # Unset means INHERIT: strip the whole setting line so the server's profile (or ClickHouse Cloud's
    # non-zero default) applies. Rendering an explicit 0 here would OVERRIDE that and force the insert
    # serial -- a silent slowdown on any deployment that already sets the setting.
    sql="$(grep -vF 'max_insert_threads = ${MAX_INSERT_THREADS}' <<<"$sql")"
else
    sql="${sql//'${MAX_INSERT_THREADS}'/$MAX_INSERT_THREADS}"
fi
# --time makes clickhouse-client print each statement's elapsed seconds to stderr (it prints nothing under a bare
# --query). The SECOND number is the deletion replay's wall time — a Go/No-Go acceptance criterion (it must fit inside
# the buffer hold with margin), so without this the operator has no way to record it short of digging in query_log.
echo "Statement wall times (seconds, in order: delta-insert, deletion-replay):"
clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --time --multiquery --query "$sql"

echo "Delta + deletion replay complete. RECORD the deletion-replay wall time above (the second value) — the"
echo "final-delta -> EXCHANGE gap must fit inside the buffer hold. Run verify.sh before the EXCHANGE."
