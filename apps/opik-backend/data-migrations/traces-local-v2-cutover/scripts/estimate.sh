#!/usr/bin/env bash
#
# Ballpark ETA for the backfill — the dominant, longest-running step (runbook: ../README.md, "Batching and throttling").
#
# It reads the live size of `traces`, estimates this instance's copy throughput with an on-the-fly READ probe (no table
# is created — a bounded `SELECT ... FORMAT Null` that reads and decompresses a sample, timed), and combines them with
# the batch/throttle config to project how long backfill.sh will take. The number is a planning ballpark, not a
# guarantee.
#
# The probe measures READ+decompress throughput only. A real `INSERT ... SELECT` also pays write+compression (ZSTD on the
# wide text columns is the bottleneck) and background merges, so the copy is slower than a bare read — that gap is folded
# in by --write-cost-factor. For an exact figure, time one real window with backfill.sh and pass its rows/sec via
# --rows-per-sec.
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).
#
# Options:
#   --database NAME            analytics database (e.g. opik). Required.
#   --max-rows-per-insert R    the value you will pass to backfill.sh; sets how many windows the copy splits into.
#                              Default 2000000 (matches backfill.sh).
#   --pause-seconds S          backfill.sh --pause-seconds; added once per window as merge-catch-up idle time. Default 0.
#   --probe-rows N             rows to read in the throughput probe (SELECT ... LIMIT N FORMAT Null). Larger = steadier
#                              estimate but a heavier probe. Default 200000. Ignored if --rows-per-sec is given.
#   --write-cost-factor F      multiplier applied to the read-probe time to account for the unmeasured write+compression
#                              +merge cost of a real copy. Default 2.5 (wide ZSTD-compressed rows are write-bound). Set 1
#                              to report the raw read-only floor. Ignored if --rows-per-sec is given.
#   --rows-per-sec R           skip the probe and use this measured COPY throughput directly (e.g. from a real backfill
#                              window). When set, --write-cost-factor is not applied.

set -euo pipefail

DATABASE=""
MAX_ROWS=2000000
PAUSE_SECONDS=0
PROBE_ROWS=200000
WRITE_COST_FACTOR=2.5
ROWS_PER_SEC=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-rows-per-insert) MAX_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --pause-seconds) PAUSE_SECONDS="${2:?"$1 requires a value"}"; shift 2 ;;
        --probe-rows) PROBE_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --write-cost-factor) WRITE_COST_FACTOR="${2:?"$1 requires a value"}"; shift 2 ;;
        --rows-per-sec) ROWS_PER_SEC="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the probe/size SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
# Numeric args flow into the probe/estimate SQL and awk; require sane numeric shapes so none can alter the query.
[[ "$MAX_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-rows-per-insert must be a positive integer." >&2; exit 2; }
[[ "$PROBE_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --probe-rows must be a positive integer." >&2; exit 2; }
[[ "$PAUSE_SECONDS" =~ ^[0-9]+$ ]] || { echo "ERROR: --pause-seconds must be a non-negative integer." >&2; exit 2; }
[[ "$WRITE_COST_FACTOR" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --write-cost-factor must be a number." >&2; exit 2; }
[[ -z "$ROWS_PER_SEC" || "$ROWS_PER_SEC" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --rows-per-sec must be a number." >&2; exit 2; }

ch() {
    clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:estimate' --query "$1"
}

# Physical rows to copy (count() honors the deleted-row mask, so masked rows are excluded — as the backfill excludes
# them) and the projected window count: each week splits until every sub-window is <= MAX_ROWS, so a week of `cnt` rows
# yields ~ceil(cnt / MAX_ROWS) inserts. Both come from one grouped scan.
read -r TOTAL_ROWS EST_WINDOWS WEEKS <<< "$(ch "
    SELECT
        sum(cnt),
        sum(if(cnt = 0, 0, toUInt64(ceil(cnt / $MAX_ROWS)))),
        count()
    FROM (
        SELECT
            toMonday(created_at) AS wk,
            count() AS cnt
        FROM traces
        GROUP BY wk
    )
    FORMAT TSV
")"

if [[ -z "$TOTAL_ROWS" || "$TOTAL_ROWS" == "0" ]]; then
    echo "Source table 'traces' is empty — nothing to backfill."
    exit 0
fi

SIZE="$(ch "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE database = '$DATABASE' AND table = 'traces' AND active")"

# Disk headroom. The backfill writes a full second physical copy of `traces` (peak ~2x on-disk, more counting merge
# scratch), so free space must clear that before starting. This is a whole-node total-space floor; on tiered storage
# validate per-volume (hot) headroom too, since new parts land on the hot volume before they tier.
TRACES_BYTES="$(ch "SELECT sum(bytes_on_disk) FROM system.parts WHERE database = '$DATABASE' AND table = 'traces' AND active")"
FREE_BYTES="$(ch "SELECT sum(free_space) FROM system.disks")"
awk -v t="$TRACES_BYTES" -v f="$FREE_BYTES" 'BEGIN {
    g = 1073741824
    printf "Disk: traces on-disk %.1f GiB, node free %.1f GiB, ~2x needed %.1f GiB%s\n",
        t/g, f/g, t*2/g, (f < t*2 ? "  *** below 2x — free space before backfilling ***" : "")
}'

# Effective COPY throughput. If the caller measured a real one, use it as-is. Otherwise probe READ throughput with an
# on-the-fly SELECT ... FORMAT Null (bounded by LIMIT, reads and decompresses ~PROBE_ROWS rows, no table created) and
# derate it by --write-cost-factor to approximate the copy's added write/merge cost.
FACTOR_NOTE=""
if [[ -z "$ROWS_PER_SEC" ]]; then
    PROBE_ACTUAL="$(awk -v a="$PROBE_ROWS" -v b="$TOTAL_ROWS" 'BEGIN { print (a < b) ? a : b }')"
    echo "Probing read throughput with a $PROBE_ACTUAL-row SELECT ... FORMAT Null (no table created)..."
    ELAPSED="$(clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:estimate' --time --query \
        "SELECT * FROM traces LIMIT $PROBE_ROWS FORMAT Null" 2>&1 1>/dev/null)"
    READ_RPS="$(awk -v r="$PROBE_ACTUAL" -v t="$ELAPSED" 'BEGIN { print (t > 0) ? r / t : 0 }')"
    [[ "$(awk -v v="$READ_RPS" 'BEGIN { print (v > 0) ? 1 : 0 }')" == "1" ]] || {
        echo "ERROR: probe measured 0 rows/sec (elapsed='$ELAPSED'). Pass --rows-per-sec." >&2
        exit 1
    }
    ROWS_PER_SEC="$(awk -v r="$READ_RPS" -v f="$WRITE_COST_FACTOR" 'BEGIN { print r / f }')"
    echo "Read throughput: ~$(printf '%.0f' "$READ_RPS") rows/sec ($PROBE_ACTUAL rows in ${ELAPSED}s)."
    FACTOR_NOTE="  (read ${READ_RPS%.*}/s derated by write-cost-factor ${WRITE_COST_FACTOR})"
fi

# ETA = copy time + total throttle idle. Throttle idle is one --pause-seconds per window (a fresh run inserts every
# window; a resumed run inserts fewer, so this is an upper bound).
awk -v rows="$TOTAL_ROWS" -v windows="$EST_WINDOWS" -v weeks="$WEEKS" -v rps="$ROWS_PER_SEC" \
    -v pause="$PAUSE_SECONDS" -v maxrows="$MAX_ROWS" -v size="$SIZE" -v note="$FACTOR_NOTE" '
function hms(s,    h, m) {
    h = int(s / 3600); s -= h * 3600
    m = int(s / 60);   s -= m * 60
    return sprintf("%dh %dm %ds", h, m, int(s))
}
BEGIN {
    copy = rows / rps
    idle = windows * pause
    total = copy + idle
    printf "\n"
    printf "Backfill estimate for %s (%s rows across %d weeks)\n", size, rows, weeks
    printf "  config:      max-rows-per-insert=%d, pause-seconds=%d\n", maxrows, pause
    printf "  windows:     ~%d inserts\n", windows
    printf "  copy rate:   ~%.0f rows/sec%s\n", rps, note
    printf "  copy time:   %s\n", hms(copy)
    printf "  throttle:    %s (%d windows x %ds)\n", hms(idle), windows, pause
    printf "  TOTAL ETA:   %s\n", hms(total)
    printf "\nBallpark only. The copy rate is derived from a read probe + write-cost-factor; for accuracy, time one real\n"
    printf "window with backfill.sh and pass its rows/sec via --rows-per-sec.\n"
}'
