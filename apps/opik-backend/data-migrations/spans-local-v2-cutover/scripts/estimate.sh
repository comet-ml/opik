#!/usr/bin/env bash
#
# Pre-cutover survey and backfill ETA for the spans cutover (runbook: ../README.md).
#
# It does three jobs, and only the third existed in the traces equivalent:
#
#   1. HEADROOM. Reports node free space against the DESTINATION's projected size rather than the source's, because
#      the source-relative gate the traces runbook used can be unsatisfiable on spans: at --min-free-factor 2.0 it
#      demands free space equal to twice the source, which a table this large need not have left. See README
#      "Blocker 1".
#   2. THE PRE-WRITE AUDITS the Go/No-Go checklist gates on, which the traces runbook left as hand-run queries: the
#      far-future / far-past id population that sizes --max-partitions-per-insert-block, and the out-of-range
#      parent_span_id values the copy normalizes. Both answer a question an operator must not guess at spans' scale,
#      so they belong in a driver rather than in prose.
#   3. THE ETA for the backfill — the dominant, longest-running step. It reads the live size of `spans`, estimates this
#      instance's copy throughput with an on-the-fly READ probe (no table is created — a bounded
#      `SELECT ... FORMAT Null` that reads and decompresses a sample, timed), and combines them with the batch/throttle
#      config to project how long backfill.sh will take. The number is a planning ballpark, not a guarantee.
#
# READ-ONLY. Every statement is a SELECT; nothing is created, written or mutated. Safe to run against production, which
# is the point — the audits it prints are production facts the rehearsal cannot supply.
#
# The probe measures READ+decompress throughput only. A real `INSERT ... SELECT` also pays write+compression (ZSTD on the
# wide text columns is the bottleneck) and background merges, so the copy is slower than a bare read — that gap is folded
# in by --write-cost-factor. For an exact figure, time one real window with backfill.sh and pass its rows/sec via
# --rows-per-sec.
#
# THIS ESTIMATE DOES NOT MODEL --max-insert-threads. The probe measures the READ side and derates it by a fixed
# --write-cost-factor, so a run configured with insert parallelism can copy substantially faster than the number
# printed here, and the estimate is not specific to your thread setting either way. If you intend to run the
# backfill with --max-insert-threads set, do not tune against this figure: time one real window at THAT setting
# and pass the result via --rows-per-sec, which bypasses the probe and the write-cost factor entirely.
#
# THE THROTTLE IS OFTEN THE BIGGER HALF, so read the two lines of the report separately. --pause-seconds is applied
# once per WINDOW, and on a large table there are thousands of windows: at 60s each that is measured in days, on its
# own. Size it against the observed system.merges backlog rather than against this ETA.
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
#   --receive-timeout N       seconds clickhouse-client waits for the NEXT PACKET before giving up (receive_timeout).
#                             Default 1800, against ClickHouse's own 300, which bounds the GAP between packets rather
#                             than total query time — so a step that goes quiet while the server works trips it while
#                             healthy. Trade-off and shared rationale: ../README.md.
#   --max-rows-per-insert R    the value you will pass to backfill.sh; sets how many windows the copy splits into.
#                              Default 1500000 (matches backfill.sh; derived in the runbook's threshold table).
#   --pause-seconds S          backfill.sh --pause-seconds; added once per window as merge-catch-up idle time. Default 0.
#   --min-free-factor F        the value you will pass to backfill.sh; applied to the PROJECTED DESTINATION size, not
#                              to the source's. Default 1.35 (matches backfill.sh). 0 skips the headroom verdict.
#   --dest-compression-ratio C the destination's projected on-disk size as a fraction of the source's. Default 0.381,
#                              measured during OPIK-7400 on the 000116 codec set. Pass a value measured on your own
#                              estate once prod-test has produced one; the runbook's acceptance criteria require that
#                              before the production window.
#   --probe-rows N             rows to read in the throughput probe (SELECT ... LIMIT N FORMAT Null). Larger = steadier
#                              estimate but a heavier probe. Default 100000. Ignored if --rows-per-sec is given.
#   --write-cost-factor F      multiplier applied to the read-probe time to account for the unmeasured write+compression
#                              +merge cost of a real copy. Default 2.5. Set 1 to report the raw read-only floor. Ignored
#                              if --rows-per-sec is given.
#   --rows-per-sec R           skip the probe and use this measured COPY throughput directly (e.g. from a real backfill
#                              window). When set, --write-cost-factor is not applied.
#   --skip-audits              print only the headroom verdict and the ETA. The audits below read every id in the table
#                              (a full scan of the id column), so on a busy production cluster they are worth
#                              scheduling rather than running casually. Do NOT skip them before the window:
#                              two of the Go/No-Go items are exactly their output.

set -euo pipefail

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
MAX_ROWS=1500000          # rows per INSERT; see backfill.sh and the runbook's threshold table.
PAUSE_SECONDS=0
MIN_FREE_FACTOR="1.35"    # multiple of the PROJECTED DESTINATION size node free space must clear. See backfill.sh.
DEST_COMPRESSION_RATIO="0.381"   # destination on-disk size as a fraction of the source's (measured in OPIK-7400).
PROBE_ROWS=100000
WRITE_COST_FACTOR=2.5
ROWS_PER_SEC=""
SKIP_AUDITS=0

# Print this driver's own header — the options documented above — so --help can never drift from them. Stops at the
# first non-comment line, and strips the leading marker. $0 is the invoked path, which is what awk needs to re-read.
usage() {
    awk 'NR == 1 { next }
         /^#/ { sub(/^# ?/, ""); if (!body && $0 == "") next; body = 1; print; next }
         { exit }' "$0"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-rows-per-insert) MAX_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --pause-seconds) PAUSE_SECONDS="${2:?"$1 requires a value"}"; shift 2 ;;
        --min-free-factor) MIN_FREE_FACTOR="${2:?"$1 requires a value"}"; shift 2 ;;
        --dest-compression-ratio) DEST_COMPRESSION_RATIO="${2:?"$1 requires a value"}"; shift 2 ;;
        --probe-rows) PROBE_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --write-cost-factor) WRITE_COST_FACTOR="${2:?"$1 requires a value"}"; shift 2 ;;
        --rows-per-sec) ROWS_PER_SEC="${2:?"$1 requires a value"}"; shift 2 ;;
        --skip-audits) SKIP_AUDITS=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2
           echo "Run '$(basename "$0") --help' for the options this driver accepts." >&2
           exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the probe/size SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
# The bracket/colon allowance is what makes an IPv6 literal usable (2001:db8::1, or [2001:db8::1]); this is a shape
# guard, not a parser, and it still admits no shell metacharacter, whitespace, quote or slash. --host is passed as its
# own argv element, never interpolated into SQL.
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^\[?[A-Za-z0-9._:-]+\]?$ ]] || { echo "ERROR: --host must be a hostname, IPv4, or IPv6 literal." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }

# One place for the connection and client-side options, so every call site below carries the same host, port,
# database, log_comment and receive_timeout, and cannot drift from the others.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT" --log_comment 'spans_local_v2_cutover:estimate')
# Numeric args flow into the probe/estimate SQL and awk; require sane numeric shapes so none can alter the query.
[[ "$MAX_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-rows-per-insert must be a positive integer." >&2; exit 2; }
[[ "$PROBE_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --probe-rows must be a positive integer." >&2; exit 2; }
[[ "$PAUSE_SECONDS" =~ ^[0-9]+$ ]] || { echo "ERROR: --pause-seconds must be a non-negative integer." >&2; exit 2; }
# STRICTLY positive, not merely numeric: this is a DIVISOR (READ_RPS / factor), and awk treats division by zero as a
# fatal error, so a 0 here would kill the run with 'awk: division by zero' instead of this refusal. The second test is
# what rejects 0, 0.0 and 0.00 while accepting 0.5 — a positive decimal has at least one non-zero digit.
[[ "$WRITE_COST_FACTOR" =~ ^[0-9]+(\.[0-9]+)?$ && "$WRITE_COST_FACTOR" =~ [1-9] ]] || { echo "ERROR: --write-cost-factor must be a number greater than zero." >&2; exit 2; }
[[ "$MIN_FREE_FACTOR" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --min-free-factor must be a number." >&2; exit 2; }
[[ "$DEST_COMPRESSION_RATIO" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --dest-compression-ratio must be a number." >&2; exit 2; }
# Same reason: ROWS_PER_SEC is the divisor in the ETA (rows / rps). Empty is still allowed — that is the "probe it"
# path, which derives the value and asserts it is positive before use.
[[ -z "$ROWS_PER_SEC" || ( "$ROWS_PER_SEC" =~ ^[0-9]+(\.[0-9]+)?$ && "$ROWS_PER_SEC" =~ [1-9] ) ]] || { echo "ERROR: --rows-per-sec must be a number greater than zero." >&2; exit 2; }

ch() {
    clickhouse-client "${CH_ARGS[@]}" --query "$1"
}

# Physical rows to copy (count() honors the deleted-row mask, so masked rows are excluded — as the backfill excludes
# them) and the projected window count: each week splits until every sub-window is <= MAX_ROWS, so a week of `cnt` rows
# yields ~ceil(cnt / MAX_ROWS) inserts. Both come from one grouped scan.
# Capture first so set -e catches a clickhouse-client failure, rather than a swallowed here-string substitution leaving
# TOTAL_ROWS empty and mislabeling the run as "table is empty".
sizing="$(ch "
    SELECT
        sum(cnt),
        sum(if(cnt = 0, 0, toUInt64(ceil(cnt / $MAX_ROWS)))),
        count()
    FROM (
        SELECT
            toMonday(created_at) AS wk,
            count() AS cnt
        FROM spans
        GROUP BY wk
    )
    FORMAT TSV
")"
read -r TOTAL_ROWS EST_WINDOWS WEEKS <<< "$sizing"

if [[ -z "$TOTAL_ROWS" || "$TOTAL_ROWS" == "0" ]]; then
    echo "Source table 'spans' is empty — nothing to backfill."
    exit 0
fi

SIZE="$(ch "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE database = '$DATABASE' AND table = 'spans' AND active")"

# Headroom, sized from the DESTINATION rather than the source. The traces gate multiplied the SOURCE size, which is
# deliberately conservative because a driver cannot know the destination's compression — and on spans that
# conservatism can be fatal rather than costly: twice this table's size can exceed the whole volume. So the projection
# is explicit (--dest-compression-ratio, defaulting to OPIK-7400's measurement) and the factor covers merge scratch on
# top of the finished copy rather than a whole second copy. Both numbers are printed, and so is the source-relative
# factor the free space actually affords, so the assumption is visible rather than buried in a verdict.
#
# This is a WHOLE-NODE total. On tiered storage it is necessary and not sufficient: new parts land on the hot volume
# before they tier, so validate per-volume headroom separately — backfill.sh requires --confirm-tiered-headroom for
# exactly that and this report cannot discharge it.
SPANS_BYTES="$(ch "SELECT sum(bytes_on_disk) FROM system.parts WHERE database = '$DATABASE' AND table = 'spans' AND active")"
FREE_BYTES="$(ch "SELECT sum(free_space) FROM system.disks")"
TOTAL_DISK_BYTES="$(ch "SELECT sum(total_space) FROM system.disks")"
# The largest lever the runbook names, and the reason it is printed here rather than left to prose: retiring the traces
# cutover's parked backup frees whatever it holds, which can be a large fraction of the free space. It is owned by
# OPIK-8263 and is irreversible, so this only SIZES it — it never suggests running anything.
TRACES_BACKUP_BYTES="$(ch "SELECT sum(bytes_on_disk) FROM system.parts WHERE database = '$DATABASE' AND table = 'traces_pre_cutover_backup' AND active")"
TRACES_BACKUP_BYTES="${TRACES_BACKUP_BYTES:-0}"

awk -v s="$SPANS_BYTES" -v f="$FREE_BYTES" -v total="$TOTAL_DISK_BYTES" -v ratio="$DEST_COMPRESSION_RATIO" \
    -v factor="$MIN_FREE_FACTOR" -v parked="$TRACES_BACKUP_BYTES" 'BEGIN {
    t = 1099511627776   # TiB
    dest = s * ratio
    need = dest * factor
    printf "\n=== Headroom ===\n"
    printf "  source `spans` on disk:        %.2f TiB\n", s/t
    printf "  projected destination:         %.2f TiB  (x%.3f, the --dest-compression-ratio)\n", dest/t, ratio
    printf "  required free:                 %.2f TiB  (x%.2f the projection, the --min-free-factor)\n", need/t, factor
    printf "  node volume free / total:      %.2f / %.2f TiB  (%.1f%% free)\n", f/t, total/t, (total > 0 ? 100.0*f/total : 0)
    if (factor > 0 && f < need) {
        printf "  VERDICT: INSUFFICIENT — short by %.2f TiB. backfill.sh will abort at startup.\n", (need - f)/t
    } else if (factor > 0) {
        printf "  VERDICT: sufficient — %.2f TiB of margin above the requirement.\n", (f - need)/t
        if (f - need < 3 * t) {
            printf "  WARNING: thin. Under 3 TiB of margin on a multi-day copy that also has to absorb merges.\n"
        }
    } else {
        printf "  VERDICT: not evaluated (--min-free-factor 0).\n"
    }
    printf "  for contrast, the traces gate multiplied the SOURCE: at its default x2.0 this table needs %.2f TiB,\n", s*2/t
    printf "  which is %s the whole volume (%.2f TiB). That is why the spans gate is destination-relative.\n",
        (s*2 > total ? "MORE THAN" : "within"), total/t
    if (parked > 0) {
        printf "  lever: `traces_pre_cutover_backup` still holds %.2f TiB. Retiring it is the single largest source of\n", parked/t
        printf "         headroom here and is owned by OPIK-8263 — it is IRREVERSIBLE (it retires the traces rollback\n"
        printf "         safety net), so it needs its own authorisation and is not a side effect of this cutover.\n"
    }
}'

if [[ "$SKIP_AUDITS" != "1" ]]; then
    # --- Audit 1: the far-future / far-past id population, and the destination partition count it implies -----------
    #
    # This is the number that sizes --max-partitions-per-insert-block, and the runbook's Go/No-Go gates on it BEFORE the
    # first write. It derives every timestamp from `id` via UUIDv7ToDateTime, NOT from the stored spans.id_at: migration
    # 000105 typed that column DateTime('UTC'), 32 bits, which wraps mod-2^32 past 2106 — so a far-future id reads back
    # as a plausible recent instant and would be undercounted here, hiding the partitions the copy will actually create.
    #
    # The partition expression is the destination's own, verbatim from migration 000115
    # (toYYYYMMDD(toDate32(ts) - toIntervalDay(toDayOfWeek(ts, 1)))), so `total_partitions` IS the number of weekly
    # partitions spans_local_v2 will hold — which is the hard upper bound on how many partitions one insert block can
    # span, and therefore the right input to the setting. Sizing from the far-future count alone undercounts it: the
    # traces measurement recorded a worst block spanning 333 partitions of which only 269 were far-future.
    echo
    echo "=== Audit 1: id-derived weekly partitions (sizes --max-partitions-per-insert-block) ==="
    ch "WITH UUIDv7ToDateTime(toUUID(id)) AS ts,
             toYYYYMMDD(toDate32(ts) - toIntervalDay(toDayOfWeek(ts, 1))) AS part
        SELECT
            uniqExact(part)                                                   AS total_partitions,
            uniqExactIf(part, ts > now() + INTERVAL 1 DAY)                    AS far_future_partitions,
            uniqExactIf(part, ts < toDateTime64('2000-01-01 00:00:00', 3, 'UTC')) AS far_past_partitions,
            countIf(ts > now() + INTERVAL 1 DAY)                              AS far_future_rows,
            countIf(ts < toDateTime64('2000-01-01 00:00:00', 3, 'UTC'))       AS far_past_rows,
            count()                                                           AS total_rows,
            min(ts)                                                           AS earliest_id_at,
            max(ts)                                                           AS latest_id_at
        FROM spans
        FORMAT Vertical"
    echo "  Size --max-partitions-per-insert-block (backfill.sh's, delta_replay.sh's and reconcile.sh's) ABOVE"
    echo "  total_partitions, with margin. A block cannot span more partitions than the table has, which is what makes"
    echo "  that a hard bound rather than an estimate. far_past_partitions counts the epoch week that non-v7 ids"
    echo "  (a v4 or nil UUID — UUIDv7ToDateTime returns 1970-01-01 for those, it does not throw) collapse into."

    # --- Audit 2: parent_span_id values the copy will normalize ----------------------------------------------------
    #
    # spans.parent_span_id is a String; spans_local_v2's is FixedString(36). SpanDAO's PARTIAL_INSERT writes
    # leftPad('', 40, '*') into it when a span's parent changes — a 40-character value the destination column cannot
    # hold — so 000001's projection maps anything that is not exactly 36 bytes to the empty (root-span) sentinel. That
    # is a deliberate normalization of a value that was never a real span id, and the Go/No-Go asks for the count
    # before the window rather than discovering it in a fidelity report afterwards. The original bytes stay in
    # spans_pre_cutover_backup until finalize.sh either way.
    echo
    echo "=== Audit 2: parent_span_id values the copy normalizes to the root sentinel ==="
    ch "SELECT
            countIf(length(parent_span_id) = 0)                              AS root_spans_empty,
            countIf(length(parent_span_id) = 36)                             AS well_formed,
            countIf(length(parent_span_id) NOT IN (0, 36))                   AS normalized_to_root,
            uniqExactIf(parent_span_id, length(parent_span_id) NOT IN (0, 36)) AS distinct_bad_values
        FROM spans
        FORMAT Vertical"
    echo "  normalized_to_root > 0 is expected on an estate that has ever patched a span's parent. Those rows are"
    echo "  copied with parent_span_id = '' (the root sentinel). Record the number; it is a Go/No-Go line item."
fi

# Effective COPY throughput. If the caller measured a real one, use it as-is. Otherwise probe READ throughput with an
# on-the-fly SELECT ... FORMAT Null (bounded by LIMIT, reads and decompresses ~PROBE_ROWS rows, no table created) and
# derate it by --write-cost-factor to approximate the copy's added write/merge cost.
FACTOR_NOTE=""
if [[ -z "$ROWS_PER_SEC" ]]; then
    PROBE_ACTUAL="$(awk -v a="$PROBE_ROWS" -v b="$TOTAL_ROWS" 'BEGIN { print (a < b) ? a : b }')"
    echo
    echo "Probing read throughput with a $PROBE_ACTUAL-row SELECT ... FORMAT Null (no table created)..."
    ELAPSED="$(clickhouse-client "${CH_ARGS[@]}" --time --query \
        "SELECT * FROM spans LIMIT $PROBE_ROWS FORMAT Null" 2>&1 1>/dev/null)"
    READ_RPS="$(awk -v r="$PROBE_ACTUAL" -v t="$ELAPSED" 'BEGIN { print (t > 0) ? r / t : 0 }')"
    [[ "$(awk -v v="$READ_RPS" 'BEGIN { print (v > 0) ? 1 : 0 }')" == "1" ]] || {
        echo "ERROR: probe measured 0 rows/sec (elapsed='$ELAPSED'). Pass --rows-per-sec." >&2
        exit 1
    }
    ROWS_PER_SEC="$(awk -v r="$READ_RPS" -v f="$WRITE_COST_FACTOR" 'BEGIN { print r / f }')"
    echo "Read throughput: ~$(printf '%.0f' "$READ_RPS") rows/sec ($PROBE_ACTUAL rows in ${ELAPSED}s)."
    # Format with printf, not "${READ_RPS%.*}": awk's default OFMT is %.6g, so any probe above ~1e6 rows/sec
    # is rendered as "1.34228e+06" and the parameter expansion strips it to "1" — reporting a fast cluster as
    # "read 1/s". Display only; ROWS_PER_SEC is passed to awk, which parses the exponent form correctly.
    FACTOR_NOTE="  (read $(printf '%.0f' "$READ_RPS")/s derated by write-cost-factor ${WRITE_COST_FACTOR})"
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
    printf "\n=== Backfill estimate ===\n"
    printf "for %s (%s rows across %d weeks)\n", size, rows, weeks
    printf "  config:      max-rows-per-insert=%d, pause-seconds=%d\n", maxrows, pause
    printf "  windows:     ~%d (one INSERT each)\n", windows
    printf "  copy rate:   ~%.0f rows/sec%s\n", rps, note
    printf "  copy time:   %s\n", hms(copy)
    printf "  throttle:    %s (%d windows x %ds)\n", hms(idle), windows, pause
    printf "  TOTAL ETA:   %s\n", hms(total)
    printf "\nBallpark only, and on this table an OPTIMISTIC one: the copy rate comes from a READ probe derated by a\n"
    printf "fixed write-cost factor, and models neither --max-insert-threads nor real merge pressure. For accuracy,\n"
    printf "time one real window with backfill.sh and pass its rows/sec via --rows-per-sec.\n"
    printf "A multi-day figure is the expected answer, not a sign the configuration is wrong. Note how much of the\n"
    printf "total above is THROTTLE: on a large table that is often the bigger half, and it is a pure choice --\n"
    printf "size --pause-seconds against the observed system.merges backlog, not against this ETA.\n"
}'
