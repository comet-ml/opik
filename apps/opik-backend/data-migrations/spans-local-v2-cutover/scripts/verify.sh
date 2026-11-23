#!/usr/bin/env bash
#
# Fidelity QA driver for the spans cutover (runbook: ../README.md, "Verifying the migration").
#
# Compares the migrated data on the old-schema and new-schema tables, week by week (created_at), using a NORMALIZED
# fingerprint so sentinel/precision differences (end_time NULL<->epoch, ttft NULL<->NaN, ns<->us) do not count as
# changes. For each week it reads one (row count, checksum) verdict per side; a mismatch means that week's live, deduped
# content differs. A differing window is then re-checked on the sorting key, which yields one of four verdicts: a real
# MISMATCH; an OK superseded-version artifact; INCONCLUSIVE, where a version tie left FINAL free to pick between rows
# that differ; or UNCERTIFIABLE, where that tie check could not be read at all. With --drill-down, any differing window
# is followed by a per-key listing. Exits non-zero if any window mismatched OR could not be certified: a gate that
# cannot decide must not pass.
#
# The compare and drill-down SQL are NOT duplicated here: both are read from db-app-analytics/000005_verify_migration.sql
# (the single source, and the exact normalization the gate test asserts). See README "Verifying the migration".
#
# THE TWO SIDES ARE DEDUPED DIFFERENTLY, AND THAT IS NOT AN ASYMMETRY TO TIDY. `spans` orders by
# (workspace_id, project_id, trace_id, parent_span_id, id) while spans_local_v2 drops parent_span_id, so the source can
# legitimately hold TWO live rows for one span where the successor holds one — SpanDAO's PARTIAL_INSERT writes a changed
# parent_span_id, and a mutable sort-key column means those versions never merge. Every block in 000005 therefore
# reduces the OLD-SCHEMA side twice (FINAL, then argMax over last_updated_at grouped by the DESTINATION key) while the
# new-schema side needs FINAL alone. Without that, a faithful copy reports a count mismatch on every such span. 000005's
# header carries the full argument; it is repeated here because an operator reading a mismatch will look at this file
# first.
#
# Cost at this scale. `spans` is unpartitioned and created_at is not in its sorting key, so a week's predicate prunes
# only through the created_at minmax skip index, granule by granule, and a granule spanning many weeks is re-read by
# every week it touches; under FINAL, use_skip_indexes_if_final_exact_mode also re-reads every selected granule's key
# neighbours, which can widen one week to most of both tables. The default one-query-per-week walk, and each
# confirm-keys re-check of a differing week, can therefore cost up to a full read of both tables. --single-pass reads
# the range once and emits the same per-week verdicts (000005's compare-weeks block); it is exact, not a sample.
# --coverage compares a time slice of every hour; it shrinks the read only as far as the tables' granules are narrow. Before the first window the driver prints
# the estimated bytes and an ETA, and at the end the elapsed time and the rate it implies, for --throughput-mbps.
# The PRE-EXCHANGE gate must still be a full compare — every week, full coverage — because a sample can miss a single-row
# deletion leak and still report ok=1; it may be split across runs when they share a pinned --anchor (see --anchor).
#
# Usage:
#   CLICKHOUSE_HOST=... CLICKHOUSE_PASSWORD=... ./verify.sh --database opik [options]
#
# Options:
#   --database NAME     analytics database (e.g. opik). Required.
#   --port N                  ClickHouse NATIVE port, when it is not the default 9000 — e.g. reaching a cluster through
#                             a port-forward or bastion on a local port. Required because clickhouse-client honors
#                             CLICKHOUSE_HOST / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment but does
#                             NOT honor CLICKHOUSE_PORT, so the port cannot be passed via env.
#   --host HOST               ClickHouse host. Pass it together with --port: clickhouse-client honors CLICKHOUSE_HOST
#                             ONLY when no connection flag is given, so supplying --port alone silently reverts the host
#                             to localhost. User/password still come from CLICKHOUSE_USER / CLICKHOUSE_PASSWORD (keeping
#                             the password out of argv).
#   --receive-timeout N       seconds clickhouse-client waits for the next packet from the server before giving up
#                             (SETTINGS receive_timeout). Default 1800, against ClickHouse's own 300. It bounds the GAP
#                             between packets, not total query time: a window compare running well past 300s does not
#                             trip it, while the post-mismatch confirm-keys re-check can — so under the stock default the
#                             first mismatching week aborts the whole run. The cost of a generous value is that a
#                             genuinely dead connection takes that long to surface; for a read-only, resumable compare,
#                             losing a long run to a transient stall is the worse failure.
#   --old-table NAME    old-schema table (Nullable, nanosecond). Default spans. After the EXCHANGE: spans_pre_cutover_backup.
#   --new-table NAME    new-schema table (sentinels, microsecond). Default spans_local_v2. After the EXCHANGE: spans.
#                       After a stage B/C ROLLBACK the defaults do not apply at all — spans_local_v2 no longer exists, so a
#                       bare run dies with "Unknown table ... spans_local_v2". The old-schema side is the restored original
#                       (`spans`) and the new-schema side is the parked successor: pass
#                       `--old-table spans --new-table spans_post_rollback_backup`, and expect the cutover window's
#                       week to legitimately mismatch by the post-cutover writes the rollback discarded — stop below it,
#                       using the offset rollback.sh prints (see README "Verifying after a rollback").
#   --coverage PCT      compare PCT% of the rows (integer 1-100). Default 100. The sample is the first 36*PCT seconds of
#                       every UTC hour by created_at: the same rows on both sides, every week and every hour represented,
#                       and — unlike a hash of the id — a range the created_at minmax skip index can skip granules on.
#                       Its bias: each project counts in proportion to its activity inside the slices, so traffic
#                       periodic on the hour is over- or under-represented. Its limit: a granule whose created_at spans
#                       more than an hour (a quiet project, non-time-ordered trace ids) is read whatever the coverage,
#                       and spans_local_v2's larger granules (index_granularity_bytes 40 MiB) span hours even for busy
#                       projects, so the read does not fall in proportion; the estimate printed before the run shows how
#                       far it falls. Server
#                       versions with use_skip_indexes_if_final_exact_mode get it disabled for the run, because that mode
#                       re-reads each selected granule's key neighbours under FINAL and would undo the skip. Not valid
#                       for the pre-EXCHANGE gate below 100.
#   --sample-mod 1      retired: a hash of the id cannot skip a granule, so N > 1 read every row for partial coverage.
#                       Only 1 is accepted, so existing invocations keep working; use --coverage.
#   --single-pass       weekly mode: read the range once and report every week's verdict from it (000005's
#                       compare-weeks), instead of one query per week. Same verdict lines, same exit status; a week that
#                       differs is resolved on its own window exactly as in the default walk. FINAL sees more of each
#                       key's versions in one wide read, so a superseded-version artifact the weekly walk reports as
#                       "OK — superseded-version artifact" may simply read OK here.
#   --batch-weeks N     with --single-pass: N selected weeks per query rather than the whole range, so an interrupted
#                       run keeps the verdicts of the batches it finished. Each batch can cost up to a full read of both
#                       tables (see "Cost at this scale"), so use as few batches as resuming allows.
#   --anchor DATE       pin the week grid to DATE (YYYY-MM-DD, a Monday) instead of reading toMonday(min(created_at))
#                       from the old table at startup. Every weekly run logs the anchor it used; pass it back with
#                       --from-week to resume, and the resumed run's offsets name exactly the windows the first run's
#                       did. Refused when later than the data's own first Monday, which would leave earlier rows out.
#                       A resumed run on a pinned anchor labels its verdict line RESUMED rather than PARTIAL.
#   --io-budget-pct N   cap the compare at N% (1-100) of the volume's throughput, given by --volume-mbps. Enforced on
#                       every query of the run as max_execution_speed_bytes, converted from disk bytes to the
#                       uncompressed bytes ClickHouse throttles on with the two tables' own compression ratio.
#   --volume-mbps M     the data volume's provisioned throughput in MB/s. Required by --io-budget-pct.
#   --throughput-mbps R the rate the ETA assumes, in MB/s of the estimated on-disk read. Default 500, an assumption:
#                       pass the rate a previous run printed at the end. Capped by --io-budget-pct when given.
#   --estimate-only     print the plan, the estimated read and the ETA, then exit 0 without comparing.
#   --from-week N       start at week offset N (0-based from the anchor Monday). Default 0. An OFFSET, not a date.
#   --to-week M         stop after week offset M (inclusive). Default: last week with data. Also an OFFSET — a YYYYMMDD
#                       partition name is rejected rather than walked as millions of empty windows.
#                       'last-sealed' stops before the current calendar week: a convenience when the compare runs in the
#                       same week as whatever it means to exclude, otherwise pass the offset explicitly.
#   --weeks-stride S    compare every S-th week (S>1 samples partitions for a quick pass). Default 1.
#   --window-from TS / --window-to TS
#                       compare ONE arbitrary created_at window instead of walking weeks — the shape the post-swap
#                       reconciliation needs, where the range of interest is "the gap", not a calendar week. Half-open
#                       [from, to). 'YYYY-MM-DD HH:MM:SS[.ffffff]', with an optional ' UTC' marker so a value pasted
#                       straight from a driver's RECORD line works; either way BOTH bounds are interpreted as UTC,
#                       matching every other window bound in this runbook.
#                       MUTUALLY EXCLUSIVE with --from-week / --to-week / --weeks-stride / --single-pass /
#                       --batch-weeks / --anchor, which all describe a week grid this mode does not build. No new compare
#                       SQL is involved: 000005's blocks are already parameterised by arbitrary ${WINDOW_LO} /
#                       ${WINDOW_HI}, and only the driver was generating week boundaries. --coverage, --drill-down,
#                       --receive-timeout, the I/O budget and the confirm-keys / version-ties resolution all work
#                       unchanged. A window holding no row on either side FAILS: nothing compared is not a pass, and an
#                       arbitrary range is easy to place where no data sits. The anchor scan is skipped (nothing needs a
#                       week grid), so the run also does not depend on min/max(created_at) holding still.
#                       IT COMPARES SPANS *CREATED* IN THE WINDOW, not every span touched in it, and the PASSED line
#                       says so. 000005's blocks bound on created_at and have to: the weekly mode's partitions and its
#                       superseded-version artifact logic are created_at-based, and widening the predicate to
#                       `created_at OR last_updated_at` would put one row in two week windows and break both. So a span
#                       created earlier and merely UPDATED inside the window is not in this compare. That is not a hole
#                       in the reconciliation — those keys are precisely what reconcile.sh's postcondition reports as
#                       stale_keys / payload_mismatch_keys, which is version-driven and sees them where a created_at
#                       window cannot. Read a PASS here as "the spans created in this range are faithful", and the four
#                       counts as what covers the ones merely updated in it.
#   --drill-down        on any week the compare reported as differing, print up to 100 keys that differ or exist on one
#                       side only. Not limited to a MISMATCH: the artifact and INCONCLUSIVE verdicts are reached from the
#                       same differing-key set, and those are the ones an operator most often needs to see.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SQL="$SCRIPT_DIR/db-app-analytics/000005_verify_migration.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
OLD_TABLE="spans"          # old-schema side; becomes spans_pre_cutover_backup after the EXCHANGE (see --old-table)
NEW_TABLE="spans_local_v2" # new-schema side (the successor being built); becomes spans after the EXCHANGE
COVERAGE=100                # percent of rows compared: a time slice of every UTC hour. See --coverage.
FROM_WEEK=0
TO_WEEK=""
WEEKS_STRIDE=1              # 1 = every week; S skips to every S-th weekly partition for a quick, pruned pass
SINGLE_PASS=0               # 1 = one read per batch of weeks (compare-weeks) instead of one query per week
BATCH_WEEKS=0               # selected weeks per single-pass query; 0 = the whole range in one query
ANCHOR_PIN=""               # pinned week-grid Monday; empty = toMonday(min(created_at)) of the old table
IO_BUDGET_PCT=""
VOLUME_MBPS=""
THROUGHPUT_MBPS=""          # empty = DEFAULT_THROUGHPUT_MBPS, reported as an assumption
DEFAULT_THROUGHPUT_MBPS=500
ESTIMATE_ONLY=0
DRILL_DOWN=0
RECEIVE_TIMEOUT=1800        # seconds tolerated between server packets, not total query time. See --receive-timeout.
WINDOW_FROM=""              # single arbitrary created_at window, half-open [from, to). See --window-from.
WINDOW_TO=""
# The grid flags carry defaults, so "was it passed?" cannot be read off their values — an explicit --from-week 0 or
# --weeks-stride 1 has to conflict with --window-from just as any other value would, or the mutual exclusion would be
# silently partial exactly where an operator is most likely to combine them by habit.
WEEK_BOUND_FLAGS=()

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
        --old-table) OLD_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --new-table) NEW_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --coverage) COVERAGE="${2:?"$1 requires a value"}"; shift 2 ;;
        --sample-mod)
            [[ "${2:?"$1 requires a value"}" == 1 ]] || {
                echo "ERROR: --sample-mod is retired. A hash of the id cannot skip a granule, so a 1/N sample still read" >&2
                echo "       every row. Use --coverage PCT, which samples a time slice the skip index can prune on." >&2
                exit 2
            }
            shift 2 ;;
        --from-week) FROM_WEEK="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --to-week) TO_WEEK="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --weeks-stride) WEEKS_STRIDE="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --single-pass) SINGLE_PASS=1; WEEK_BOUND_FLAGS+=("$1"); shift ;;
        --batch-weeks) BATCH_WEEKS="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --anchor) ANCHOR_PIN="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --io-budget-pct) IO_BUDGET_PCT="${2:?"$1 requires a value"}"; shift 2 ;;
        --volume-mbps) VOLUME_MBPS="${2:?"$1 requires a value"}"; shift 2 ;;
        --throughput-mbps) THROUGHPUT_MBPS="${2:?"$1 requires a value"}"; shift 2 ;;
        --estimate-only) ESTIMATE_ONLY=1; shift ;;
        --window-from) WINDOW_FROM="${2:?"$1 requires a value"}"; shift 2 ;;
        --window-to) WINDOW_TO="${2:?"$1 requires a value"}"; shift 2 ;;
        --drill-down) DRILL_DOWN=1; shift ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2
           echo "Run '$(basename "$0") --help' for the options this driver accepts." >&2
           exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database / --old-table / --new-table are interpolated into the reference SQL; require plain ClickHouse identifiers.
for _ident in "$DATABASE" "$OLD_TABLE" "$NEW_TABLE"; do
    [[ "$_ident" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database/--old-table/--new-table must be ClickHouse identifiers (letters, digits, underscore): '$_ident'" >&2; exit 2; }
done
# Numeric args are interpolated into the reference SQL / week arithmetic; require integer shapes so none can alter it.
# The bracket/colon allowance is what makes an IPv6 literal usable (2001:db8::1, or [2001:db8::1]); this is a shape
# guard, not a parser, and it still admits no shell metacharacter, whitespace, quote or slash. --host is passed as its
# own argv element, never interpolated into SQL.
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^\[?[A-Za-z0-9._:-]+\]?$ ]] || { echo "ERROR: --host must be a hostname, IPv4, or IPv6 literal." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$COVERAGE" =~ ^[1-9][0-9]*$ ]] && (( ${#COVERAGE} <= 3 && COVERAGE <= 100 )) \
    || { echo "ERROR: --coverage must be an integer percentage from 1 to 100." >&2; exit 2; }
# The week bounds are capped at nine digits, lexically and before any arithmetic: bash wraps silently past 2^63, and a
# wrapped stride or batch span turns negative and steps the week loops below backwards forever. Nine digits still lets
# a YYYYMMDD partition name through to the clearer --to-week error further down. No leading zeros either: bash reads
# 08 as invalid octal.
[[ "$FROM_WEEK" =~ ^(0|[1-9][0-9]{0,8})$ ]] \
    || { echo "ERROR: --from-week must be a non-negative integer (a week offset)." >&2; exit 2; }
[[ "$WEEKS_STRIDE" =~ ^[1-9][0-9]{0,8}$ ]] || { echo "ERROR: --weeks-stride must be a positive integer." >&2; exit 2; }
[[ "$BATCH_WEEKS" =~ ^(0|[1-9][0-9]{0,5})$ ]] \
    || { echo "ERROR: --batch-weeks must be a non-negative integer (0 = the whole range in one query)." >&2; exit 2; }
(( BATCH_WEEKS == 0 || SINGLE_PASS == 1 )) \
    || { echo "ERROR: --batch-weeks sizes the queries of --single-pass; pass --single-pass with it." >&2; exit 2; }
[[ -z "$ANCHOR_PIN" || "$ANCHOR_PIN" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || { echo "ERROR: --anchor must be a date, YYYY-MM-DD (the Monday a previous run logged as its anchor)." >&2; exit 2; }
# Lexical digit-count checks before any arithmetic: bash wraps silently past 2^63, and a wrapped value would pass a
# range test and derive a nonsensical cap.
[[ -z "$IO_BUDGET_PCT" ]] || { [[ "$IO_BUDGET_PCT" =~ ^[1-9][0-9]{0,2}$ ]] && (( IO_BUDGET_PCT <= 100 )); } \
    || { echo "ERROR: --io-budget-pct must be an integer percentage from 1 to 100." >&2; exit 2; }
[[ -z "$VOLUME_MBPS" || "$VOLUME_MBPS" =~ ^[1-9][0-9]{0,6}$ ]] \
    || { echo "ERROR: --volume-mbps must be a positive integer (MB/s)." >&2; exit 2; }
[[ -z "$THROUGHPUT_MBPS" || "$THROUGHPUT_MBPS" =~ ^[1-9][0-9]{0,6}$ ]] \
    || { echo "ERROR: --throughput-mbps must be a positive integer (MB/s)." >&2; exit 2; }
if [[ -n "$IO_BUDGET_PCT" && -z "$VOLUME_MBPS" ]] || [[ -z "$IO_BUDGET_PCT" && -n "$VOLUME_MBPS" ]]; then
    echo "ERROR: --io-budget-pct and --volume-mbps go together: the budget is a share of the volume's provisioned" >&2
    echo "       throughput, which ClickHouse cannot discover." >&2
    exit 2
fi
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }
[[ -z "$TO_WEEK" || "$TO_WEEK" == last-sealed || "$TO_WEEK" =~ ^(0|[1-9][0-9]{0,8})$ ]] \
    || { echo "ERROR: --to-week must be a non-negative integer or 'last-sealed'." >&2; exit 2; }
[[ -f "$VERIFY_SQL" ]] || { echo "ERROR: cannot find verify SQL at $VERIFY_SQL" >&2; exit 2; }

# Window mode: one arbitrary created_at range instead of a week grid. The two modes cannot be combined — the week bounds
# are offsets into a grid this mode never builds — so refuse rather than silently letting one win.
WINDOW_MODE=0
if [[ -n "$WINDOW_FROM" || -n "$WINDOW_TO" ]]; then
    WINDOW_MODE=1
    [[ -n "$WINDOW_FROM" && -n "$WINDOW_TO" ]] \
        || { echo "ERROR: --window-from and --window-to must be passed together (the window is half-open [from, to))." >&2; exit 2; }
    if (( ${#WEEK_BOUND_FLAGS[@]} > 0 )); then
        echo "ERROR: --window-from/--window-to are mutually exclusive with ${WEEK_BOUND_FLAGS[*]}. Those describe a" >&2
        echo "       weekly grid anchored on a Monday; window mode compares one explicit range and builds no grid at" >&2
        echo "       all, so there is nothing for them to mean." >&2
        exit 2
    fi
    # Accept the ' UTC' marker the drivers print, so a value pasted from a RECORD line works, but do not require it: the
    # bounds are interpreted as UTC either way (the SQL pins 'UTC'), which is what --sentinel-window-from also does. The
    # consequence of a wrong zone here is a shifted comparison window, not a mutation — unlike the anchors reconcile.sh
    # and rollback.sh take, where the marker is mandatory because a shifted value silently destroys or resurrects rows.
    WINDOW_FROM="${WINDOW_FROM% UTC}"
    WINDOW_TO="${WINDOW_TO% UTC}"
    for _bound in "$WINDOW_FROM" "$WINDOW_TO"; do
        [[ "$_bound" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?$ ]] \
            || { echo "ERROR: --window-from/--window-to must be 'YYYY-MM-DD HH:MM:SS[.ffffff]' (an optional ' UTC' marker is accepted): '$_bound'" >&2; exit 2; }
    done
    # Compare on a fraction padded to DateTime64(6)'s six digits, not on the bounds as given. Lexical order is
    # chronological for these strings in every case but one: when one fraction is a prefix of the other, the same
    # instant at two precisions ('10:00:00' and '10:00:00.000000') compares as strictly ordered, so the EMPTY range it
    # names passes the check below and the run reports PASSED over nothing — the one answer a fidelity gate must never
    # give, and one the checked==0 guard does not catch, since window mode always compares exactly one window. That
    # shape is reachable: the check above permits a variable-length fraction, and the bounds come from different
    # places, one pasted from a driver's RECORD line and one typed.
    _cmp=()
    for _bound in "$WINDOW_FROM" "$WINDOW_TO"; do
        _frac="000000"
        [[ "$_bound" != *.* ]] || _frac="${_bound#*.}000000"
        _cmp+=("${_bound%%.*}.${_frac:0:6}")
    done
    [[ "${_cmp[0]}" < "${_cmp[1]}" ]] \
        || { echo "ERROR: --window-from must be strictly before --window-to (the window is half-open, so an empty range compares nothing)." >&2; exit 2; }
fi

# One place for the connection and client-side options, so the four call sites below cannot drift — in particular so
# --receive-timeout applies to the confirm-keys re-check and the drill-down, not only to the window compare.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --log_comment 'spans_local_v2_cutover:verify' --receive_timeout="$RECEIVE_TIMEOUT")

# --format TabSeparated is explicit, not redundant: clickhouse-client takes a default format from the user's own client
# config, and a pretty/bordered default would put headers and box-drawing into every scalar read below. Those are parsed
# as scalars — counts, timestamps and names — so the failure would not be an error, it would be a wrong verdict.
ch() {
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --query "$1"
}

log() {
    echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
}

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block from the reference SQL (exact-line markers), and
# substitute this window's placeholders.
render_block() {
    local block="$1" lo="$2" hi="$3" sql begins ends masked begin_line end_line
    # Exactly one pair: the awk otherwise runs to EOF on a missing END and sweeps the later blocks in with this one.
    begins="$(grep -cxF -e "-- >>> BEGIN $block" "$VERIFY_SQL" || true)"
    ends="$(grep -cxF -e "-- >>> END $block" "$VERIFY_SQL" || true)"
    if (( begins != 1 || ends != 1 )); then
        log "ERROR: $VERIFY_SQL holds $begins '-- >>> BEGIN $block' and $ends '-- >>> END $block'; expected one of each." >&2
        return 1
    fi
    # Counting alone misses an END moved ABOVE its BEGIN, which still counts 1 and 1 and gives the same run-on capture.
    # Here that costs queries rather than correctness: every block is a read, and each caller parses the first row,
    # which still comes from the intended statement. Refused anyway -- drill-down and the two re-checks are not cheap
    # to run per window by accident, and "the caller happens to parse the right row" is not a property to depend on.
    read -r begin_line end_line <<<"$(awk -v b="-- >>> BEGIN $block" -v e="-- >>> END $block" \
        '$0 == b {bl = NR} $0 == e {el = NR} END {print bl, el}' "$VERIFY_SQL")"
    if (( end_line <= begin_line )); then
        log "ERROR: $VERIFY_SQL has the '$block' markers out of order (BEGIN at line $begin_line, END at line" >&2
        log "       $end_line), so the block would capture to end of file. Refusing to run it." >&2
        return 1
    fi
    sql="$(awk -v begin="-- >>> BEGIN $block" -v end="-- >>> END $block" \
        '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$VERIFY_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${OLD_TABLE}'/$OLD_TABLE}"
    sql="${sql//'${NEW_TABLE}'/$NEW_TABLE}"
    sql="${sql//'${WINDOW_LO}'/$lo}"
    sql="${sql//'${WINDOW_HI}'/$hi}"
    sql="${sql//'${ROW_FILTER}'/$ROW_FILTER}"
    sql="${sql//'${ANCHOR}'/${ANCHOR:-}}"
    sql="${sql//'${FROM_WEEK}'/$FROM_WEEK}"
    sql="${sql//'${WEEKS_STRIDE}'/$WEEKS_STRIDE}"
    # A renamed, moved or split marker yields text that is empty or only the block's own comments, and clickhouse-client
    # exits 0 on either, so the caller would read "no output" as a verdict rather than as a failure to ask.
    #
    # RETURN, not exit: every caller invokes this inside a command substitution, where an exit ends only the subshell
    # and would leave the outer clickhouse-client running with an empty --query. The callers assign first, so a
    # non-zero return trips `set -e` there.
    #
    # Masked first because every check below needs the executable text: comments are not whitespace, and a block's own
    # prose can contain the token that identifies it.
    masked="$(sed 's/--.*$//' <<<"$sql")"
    if [[ -z "${masked//[[:space:]]/}" ]]; then
        log "ERROR: the '$block' block from $VERIFY_SQL rendered no executable SQL (empty, or comments only)." >&2
        log "       Expected the exact marker lines '-- >>> BEGIN $block' and '-- >>> END $block'." >&2
        return 1
    fi
    # Markers that slipped onto prose or a non-statement leave plenty of text, so emptiness cannot catch that; every
    # block here is a read, so requiring a SELECT does. Note the limit: this cannot tell one block's SELECT from
    # another's, which would need a per-block token. It does not have to — the callers validate the shape of what comes
    # back (`ok` must be 1, confirm-keys must be a count), so a block swapped for another read fails closed there.
    if ! grep -qF 'SELECT' <<<"$masked"; then
        log "ERROR: the '$block' block from $VERIFY_SQL has no SELECT outside its comments, so the markers are not" >&2
        log "       around a statement. Refusing to run it." >&2
        return 1
    fi
    # A surviving placeholder means a substitution was missed — a moved marker, or one added to the block and not to the
    # list above. ClickHouse would reject the literal anyway; refusing here names the actual cause instead.
    if grep -qF '${' <<<"$masked"; then
        log "ERROR: the '$block' block from $VERIFY_SQL still holds an unsubstituted \${...} placeholder after" >&2
        log "       rendering. Refusing to send SQL containing a literal placeholder." >&2
        return 1
    fi
    printf '%s' "$sql"
}

# --coverage's slice, the first COVERAGE*36 seconds of every UTC hour. Written with toMinute/toSecond because each is
# monotonic within an hour, which is what lets the created_at minmax index evaluate it on a granule whose rows sit in
# one hour; created_at is a 'UTC' column, so both read UTC whatever the session timezone.
ROW_FILTER=1
if (( COVERAGE < 100 )); then
    SLICE_SECONDS=$(( COVERAGE * 36 ))
    ROW_FILTER="(toMinute(created_at) < $(( SLICE_SECONDS / 60 )) OR (toMinute(created_at) = $(( SLICE_SECONDS / 60 ))"
    ROW_FILTER+=" AND toSecond(created_at) < $(( SLICE_SECONDS % 60 ))))"
fi

# Verdict TSV row for one window: src_rows dst_rows src_checksum dst_checksum ok
compare_window() {
    local sql
    sql="$(render_block compare "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery --query "$sql"
}

# Verdict TSV rows for every week of [$1, $2) holding data: week src_rows dst_rows src_checksum dst_checksum ok
compare_weeks() {
    local sql
    sql="$(render_block compare-weeks "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery --query "$sql"
}

# Per-key differences for one window (only run on a mismatch, under --drill-down).
# Returns rather than exits on a render failure: the call site treats a failed drill-down as non-fatal, and an exit
# here would abort a run whose verdicts were already decided.
drill_down_window() {
    local sql
    sql="$(render_block drill-down "$1" "$2")" || return 1
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery --query "$sql"
}

# Count of keys in one window that GENUINELY differ, re-checked on the sorting key so FINAL cannot hide a
# version (see the confirm-keys block for why a created_at window can surface a superseded row on one side
# only). 0 means the window's difference is a superseded-version artifact, not a data difference — provided no
# key's newest version is tied, which version_ties_window answers next.
confirm_keys_window() {
    local sql
    sql="$(render_block confirm-keys "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery --query "$sql"
}

# Per side, how many keys in one window carry MORE THAN ONE DISTINCT ROW at their newest last_updated_at — i.e. where
# FINAL had to choose between rows that differ. Run on ANY differing window, because a tie reaches both verdicts: where
# the two sides' arbitrary picks COINCIDE the window lands on confirm-keys = 0 and this DECIDES it; where they DIFFER
# the key counts as genuinely differing and this DIAGNOSES the MISMATCH — which is the branch the spans-only tie
# usually takes. See the version-ties block for why it is a separate statement, and why the count is an upper bound
# over the window rather than a per-key attribution (so it explains differing keys without clearing them).
version_ties_window() {
    local sql
    sql="$(render_block version-ties "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery --query "$sql"
}

ROWS="$(ch "SELECT count() FROM $OLD_TABLE")"
if [[ "$ROWS" == "0" ]]; then
    # An empty old table is only "nothing to verify" if the new table is ALSO empty. If the successor has rows the
    # source doesn't, that's an unexplained divergence (extra destination rows) — fail rather than declare success.
    NEW_ROWS="$(ch "SELECT count() FROM $NEW_TABLE")"
    if [[ "$NEW_ROWS" == "0" ]]; then
        log "Both '$OLD_TABLE' and '$NEW_TABLE' are empty — nothing to verify."
        exit 0
    fi
    log "FAILED: '$OLD_TABLE' is empty but '$NEW_TABLE' has $NEW_ROWS row(s) — the successor holds data the source does not." >&2
    exit 1
fi

# Week range from the old table's created_at (bounded and real; covers rows whose id_at is far-future from the bad-id bug
# but whose created_at is real). Same anchor math as backfill.sh. Skipped entirely in window mode: the bounds are given,
# so there is no grid to anchor — and with it goes this scan's one liability, that both bounds are read live and can move
# under a table still taking writes.
#
# --anchor pins the grid instead. Read live, the anchor moves whenever the oldest row does (retention, a late backdated
# write), and then two runs' offsets name different windows; pinned, a resumed run is continuous with the one it
# follows by construction. A pin later than the data's own first Monday is refused: the weeks before it would never be
# compared. An earlier one only adds empty leading weeks.
resolve_week_grid() {
    DATA_ANCHOR="$(ch "SELECT toString(toMonday(min(created_at))) FROM $OLD_TABLE")"
    if [[ -z "$ANCHOR_PIN" ]]; then
        ANCHOR="$DATA_ANCHOR"
        ANCHOR_SOURCE="read from toMonday(min(created_at)) of '$OLD_TABLE'"
    else
        if [[ "$(ch "SELECT toDayOfWeek(toDateOrNull('$ANCHOR_PIN'))")" != 1 ]]; then
            echo "ERROR: --anchor $ANCHOR_PIN is not a valid Monday. The week grid starts on a Monday; pass the anchor a" >&2
            echo "       previous run logged." >&2
            exit 2
        fi
        if [[ "$ANCHOR_PIN" > "$DATA_ANCHOR" ]]; then
            echo "ERROR: --anchor $ANCHOR_PIN is later than the first week holding data ($DATA_ANCHOR), so the rows" >&2
            echo "       before it would never be compared. Pin $DATA_ANCHOR or earlier." >&2
            exit 2
        fi
        ANCHOR="$ANCHOR_PIN"
        ANCHOR_SOURCE="pinned with --anchor"
    fi
    HORIZON="$(ch "SELECT toString(addWeeks(toMonday(max(created_at)), 1)) FROM $OLD_TABLE")"
    LAST_WEEK="$(ch "SELECT dateDiff('week', toDate('$ANCHOR'), toDate('$HORIZON')) - 1")"
    [[ -n "$TO_WEEK" ]] || TO_WEEK="$LAST_WEEK"
    # Every week past the last one with data is empty by construction, so a larger --to-week only walks empty windows. This
    # also catches the realistic mix-up: a weekly PARTITION name passes the integer test and would otherwise walk millions of
    # empty windows with no error and no result. The anchor comes from created_at above, so far-future id_at values never
    # inflate a legitimate offset however far ahead they sit — only what the caller typed can be out of range.
    if [[ "$TO_WEEK" =~ ^[0-9]+$ ]] && (( TO_WEEK > LAST_WEEK )); then
        echo "ERROR: --to-week $TO_WEEK is past the last week with data (offset $LAST_WEEK). These bounds are 0-based week" >&2
        echo "       OFFSETS from the anchor Monday ($ANCHOR), not dates — if that was a YYYYMMDD partition name, pass an" >&2
        echo "       offset instead, or 'last-sealed' for the last complete week, or omit the bound to cover every week." >&2
        echo "       To compare one explicit time range instead of a week grid, use --window-from/--window-to." >&2
        exit 2
    fi
    # 'last-sealed' excludes the current CALENDAR week — the only one that can still change — and not merely the newest week
    # holding data: on a quiet table max(created_at) may already sit in a sealed week, and excluding that one would leave the
    # newest populated week uncompared. Capped at LAST_WEEK so a quiet table still verifies everything it holds. now('UTC')
    # matches created_at's own timezone, so the boundary agrees with the anchor even where the server timezone is not UTC.
    if [[ "$TO_WEEK" == last-sealed ]]; then
        CURRENT_WEEK="$(ch "SELECT dateDiff('week', toDate('$ANCHOR'), toDate(toMonday(now('UTC'))))")"
        TO_WEEK=$(( LAST_WEEK < CURRENT_WEEK - 1 ? LAST_WEEK : CURRENT_WEEK - 1 ))
        (( TO_WEEK >= FROM_WEEK )) || {
            echo "ERROR: --to-week last-sealed resolved to week $TO_WEEK, which is before --from-week $FROM_WEEK: all of" >&2
            echo "       '$OLD_TABLE' sits in the current (unsealed) week, so there is no sealed week to compare. Verify" >&2
            echo "       once a week has closed, or pass an explicit --to-week to include the current one." >&2
            exit 2
        }
    fi
}

# The table holding the parts: the table itself, or a Distributed wrapper's target once the estate is wrapped.
# system.parts and EXPLAIN ESTIMATE read storage, and the wrapper has none.
storage_table() {
    if [[ "$(ch "SELECT engine FROM system.tables WHERE database = currentDatabase() AND name = '$1'")" == Distributed ]]; then
        ch "SELECT extractAll(engine_full, '\\'([^\\']*)\\'')[3] FROM system.tables
            WHERE database = currentDatabase() AND name = '$1'"
    else
        echo "$1"
    fi
}
OLD_STORAGE="$(storage_table "$OLD_TABLE")"
NEW_STORAGE="$(storage_table "$NEW_TABLE")"

# On-disk bytes per row of a table: what turns an estimated row count into an estimated read.
disk_bytes_per_row() {
    ch "SELECT if(sum(rows) = 0, 0, sum(bytes_on_disk) / sum(rows)) FROM system.parts
        WHERE active AND database = currentDatabase() AND table = '$1'"
}
OLD_BYTES_PER_ROW="$(disk_bytes_per_row "$OLD_STORAGE")"
NEW_BYTES_PER_ROW="$(disk_bytes_per_row "$NEW_STORAGE")"

human_bytes() {
    awk -v b="$1" 'BEGIN { if (b >= 1073741824) printf "%.1f GiB", b / 1073741824; else printf "%.1f MiB", b / 1048576 }'
}
human_duration() { awk -v s="$1" 'BEGIN { s = int(s + 0.5); printf "%dh%02dm%02ds", s / 3600, (s % 3600) / 60, s % 60 }'; }

# The budget is a share of the volume's disk throughput, but ClickHouse throttles on the uncompressed bytes it reads,
# so the cap is scaled by the two tables' compression ratio. Applied to every query of the run, the re-checks of a
# differing window included, and checked from the first second rather than after the server's default grace period.
EFFECTIVE_MBPS="${THROUGHPUT_MBPS:-$DEFAULT_THROUGHPUT_MBPS}"
RATE_SOURCE="${THROUGHPUT_MBPS:+given with --throughput-mbps}"
RATE_SOURCE="${RATE_SOURCE:-assumed; pass --throughput-mbps with the rate a previous run measured}"
if [[ -n "$IO_BUDGET_PCT" ]]; then
    COMPRESSION_RATIO="$(ch "SELECT greatest(1, sum(data_uncompressed_bytes) / greatest(sum(data_compressed_bytes), 1))
        FROM system.parts WHERE active AND database = currentDatabase() AND table IN ('$OLD_STORAGE', '$NEW_STORAGE')")"
    BUDGET_MBPS=$(( VOLUME_MBPS * IO_BUDGET_PCT / 100 ))
    (( BUDGET_MBPS > 0 )) || BUDGET_MBPS=1
    MAX_SPEED_BYTES="$(awk -v m="$BUDGET_MBPS" -v r="$COMPRESSION_RATIO" 'BEGIN { printf "%.0f", m * 1000000 * r }')"
    CH_ARGS+=(--max_execution_speed_bytes="$MAX_SPEED_BYTES" --timeout_before_checking_execution_speed=0)
    if (( BUDGET_MBPS < EFFECTIVE_MBPS )); then
        EFFECTIVE_MBPS="$BUDGET_MBPS"
        RATE_SOURCE="the I/O budget"
    fi
fi

# After the budget, so the grid's min/max(created_at) scans of the old table run under the cap too.
(( WINDOW_MODE == 1 )) || resolve_week_grid

# Under FINAL, servers that have use_skip_indexes_if_final_exact_mode re-read the key neighbours of every granule a skip
# index selects. That keeps FINAL exact for a created_at window, but the coverage slices select granules scattered
# across the whole table and the neighbours cover nearly all of it, undoing the skip. Without it a
# slice can surface a superseded version on one side, which is the artifact confirm-keys already resolves.
if (( COVERAGE < 100 )) \
    && [[ "$(ch "SELECT count() FROM system.settings WHERE name = 'use_skip_indexes_if_final_exact_mode'")" == 1 ]]; then
    CH_ARGS+=(--use_skip_indexes_if_final_exact_mode=0)
fi

# Granule-level estimate of what one compare reads over [$1, $2), both sides, in on-disk bytes. EXPLAIN ESTIMATE runs
# the index analysis only — primary key, the created_at skip index, the coverage slice — and reads no data.
# Returns non-zero when either EXPLAIN fails, so a client error is never read as a zero-byte estimate; a window the
# index analysis selects nothing in returns no rows and correctly estimates 0.
estimate_window_bytes() {
    local old_plan new_plan old_rows new_rows
    old_plan="$(ch "EXPLAIN ESTIMATE SELECT count() FROM $OLD_STORAGE FINAL
        WHERE created_at >= toDateTime64('$1', 9, 'UTC') AND created_at < toDateTime64('$2', 9, 'UTC') AND $ROW_FILTER
        SETTINGS use_skip_indexes_if_final = 1")" || return 1
    new_plan="$(ch "EXPLAIN ESTIMATE SELECT count() FROM $NEW_STORAGE FINAL
        WHERE created_at >= toDateTime64('$1', 6, 'UTC') AND created_at < toDateTime64('$2', 6, 'UTC') AND $ROW_FILTER
        SETTINGS use_skip_indexes_if_final = 1")" || return 1
    old_rows="$(awk -F'\t' '{ r += $4 } END { print r + 0 }' <<<"$old_plan")"
    new_rows="$(awk -F'\t' '{ r += $4 } END { print r + 0 }' <<<"$new_plan")"
    awk -v o="$old_rows" -v ob="$OLD_BYTES_PER_ROW" -v n="$new_rows" -v nb="$NEW_BYTES_PER_ROW" \
        'BEGIN { printf "%.0f %.0f\n", o * ob, n * nb }'
}

eta_seconds() { awk -v b="$1" -v r="$EFFECTIVE_MBPS" 'BEGIN { printf "%.0f", b / (r * 1000000) }'; }

# The queries this run issues, as half-open created_at bounds: one window, one per selected week, or one per
# --single-pass batch. Week starts come from one query rather than two per week.
QUERY_LO=()
QUERY_HI=()
QUERY_FIRST_WEEK=()
QUERY_LAST_WEEK=()
if (( WINDOW_MODE == 1 )); then
    QUERY_LO=("$WINDOW_FROM")
    QUERY_HI=("$WINDOW_TO")
else
    # Index i holds the Monday of week FROM_WEEK + i, through TO_WEEK + 1 (the last window's upper bound).
    # Captured first, so a failed query aborts here under set -e rather than surfacing later as an empty array.
    week_starts="$(ch "SELECT arrayStringConcat(arrayMap(w -> toString(addWeeks(toDate('$ANCHOR'), w)),
        range($FROM_WEEK, greatest($FROM_WEEK, $TO_WEEK + 2))), ' ')")"
    read -r -a WEEK_STARTS <<<"$week_starts"
    week_start() { echo "${WEEK_STARTS[$(( $1 - FROM_WEEK ))]} 00:00:00"; }
    if (( SINGLE_PASS == 1 )); then
        # A batch spans BATCH_WEEKS selected weeks, so a multiple of the stride, which keeps every batch on the stride's
        # phase (week - FROM_WEEK divisible by the stride) that compare-weeks filters on.
        batch_span=$(( BATCH_WEEKS == 0 ? TO_WEEK - FROM_WEEK + 1 : BATCH_WEEKS * WEEKS_STRIDE ))
        for (( first=FROM_WEEK; first<=TO_WEEK; first+=batch_span )); do
            last=$(( first + batch_span - 1 < TO_WEEK ? first + batch_span - 1 : TO_WEEK ))
            QUERY_FIRST_WEEK+=("$first")
            QUERY_LAST_WEEK+=("$last")
            QUERY_LO+=("$(week_start "$first")")
            QUERY_HI+=("$(week_start $(( last + 1 )))")
        done
    else
        for (( week=FROM_WEEK; week<=TO_WEEK; week+=WEEKS_STRIDE )); do
            QUERY_FIRST_WEEK+=("$week")
            QUERY_LAST_WEEK+=("$week")
            QUERY_LO+=("$(week_start "$week")")
            QUERY_HI+=("$(week_start $(( week + 1 )))")
        done
    fi
fi

if (( COVERAGE == 100 )); then
    SAMPLE_LABEL="sample 1/1"
else
    SAMPLE_LABEL="coverage $COVERAGE%"
fi
if (( WINDOW_MODE == 1 )); then
    log "Verify: $OLD_TABLE vs $NEW_TABLE | window [$WINDOW_FROM .. $WINDOW_TO) UTC | $SAMPLE_LABEL"
else
    log "Verify: $OLD_TABLE vs $NEW_TABLE | weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE | $SAMPLE_LABEL"
    log "Week grid: anchor $ANCHOR, $ANCHOR_SOURCE. To resume on this grid: --anchor $ANCHOR --from-week <next week>."
fi
if (( SINGLE_PASS == 1 )); then
    log "Single pass: ${#QUERY_LO[@]} quer$( (( ${#QUERY_LO[@]} == 1 )) && echo y || echo ies) over the range," \
        "each week's verdict read from it (000005 compare-weeks)."
fi
if (( COVERAGE < 100 )); then
    log "Coverage: $COVERAGE% — spans created in the first $SLICE_SECONDS s of every UTC hour. Same rows on both sides;"
    log "  every week and every hour is represented, each project in proportion to its activity inside the slices, so"
    log "  traffic periodic on the hour is over- or under-represented. Granules spanning more than an hour, on either"
    log "  table, are read in full whatever the coverage, so the read does not fall in proportion. NOT valid as the"
    log "  pre-EXCHANGE gate."
fi
if [[ -n "$IO_BUDGET_PCT" ]]; then
    log "I/O budget: $IO_BUDGET_PCT% of $VOLUME_MBPS MB/s = $BUDGET_MBPS MB/s of disk reads, enforced on every query as" \
        "max_execution_speed_bytes=$MAX_SPEED_BYTES (uncompressed, at the tables' ${COMPRESSION_RATIO}:1 compression)."
fi

EST_OLD=0
EST_NEW=0
QUERY_EST=()
for i in "${!QUERY_LO[@]}"; do
    estimate="$(estimate_window_bytes "${QUERY_LO[$i]}" "${QUERY_HI[$i]}")" || {
        log "ERROR: EXPLAIN ESTIMATE failed for [${QUERY_LO[$i]}, ${QUERY_HI[$i]}); see the client error above." >&2
        exit 2
    }
    read -r est_old est_new <<<"$estimate"
    QUERY_EST+=($(( est_old + est_new )))
    EST_OLD=$(( EST_OLD + est_old ))
    EST_NEW=$(( EST_NEW + est_new ))
done
EST_BYTES=$(( EST_OLD + EST_NEW ))
EST_SECONDS="$(eta_seconds "$EST_BYTES")"
log "Estimate: ~$(human_bytes "$EST_BYTES") read over ${#QUERY_LO[@]} compare quer$( (( ${#QUERY_LO[@]} == 1 )) && echo y || echo ies)" \
    "('$OLD_TABLE' $(human_bytes "$EST_OLD"), '$NEW_TABLE' $(human_bytes "$EST_NEW")) — ETA ~$(human_duration "$EST_SECONDS")" \
    "at $EFFECTIVE_MBPS MB/s ($RATE_SOURCE). Granule-level, from the index analysis; a differing window adds its re-checks."
if (( ESTIMATE_ONLY == 1 )); then
    log "Estimate only: nothing was compared."
    exit 0
fi

# The run measured against the estimate above. The rate is the ESTIMATED bytes of the queries that completed over the
# elapsed time — the quantity the ETA divides — so passing it back as --throughput-mbps makes the next ETA match this
# run's pace, an aborted run included. Runs on every exit from here on and never changes the exit status.
DONE_EST_BYTES=0
report_actuals() {
    local elapsed=$(( SECONDS - RUN_T0 )) rate
    local line="Actual vs predicted: $(human_duration "$elapsed") elapsed against an ETA of"
    line+=" ~$(human_duration "$EST_SECONDS") for ~$(human_bytes "$EST_BYTES")."
    if (( elapsed > 0 && DONE_EST_BYTES > 0 )); then
        rate=$(( DONE_EST_BYTES / elapsed / 1000000 ))
        (( rate > 0 )) || rate=1
        line+=" The queries that completed were estimated at ~$(human_bytes "$DONE_EST_BYTES"), re-checks included:"
        line+=" ~$rate MB/s, so pass --throughput-mbps $rate next time."
    fi
    log "$line"
}
RUN_T0=$SECONDS
trap report_actuals EXIT

mismatches=0
artifacts=0
inconclusive=0
uncertifiable=0
checked=0
rows_compared=0
empty_windows=0
# One window, verdict and all. Factored out so the week grid, the single-pass batches and the single arbitrary window
# share EXACTLY this logic — the confirm-keys re-check, the version-ties decision, the drill-down and every verdict
# counter. A second copy would be a second place for a fidelity verdict to be decided, which is the last thing this file
# should hold two of. $1 labels the window in the log ("week 3", or "window"); $2/$3 are its half-open bounds; $4, when
# given, is the window's verdict row already read by compare-weeks, so the compare is not issued again.
compare_one_window() {
    local label="$1" LO="$2" HI="$3"
    local compare_out src_rows dst_rows src_checksum dst_checksum ok window_certified unresolved ties_out src_ties dst_ties
    local recheck_bytes

    # Capture first (not read <<< "$(...)"): a here-string command substitution is exempt from set -e, so a
    # clickhouse-client failure here would be swallowed, leaving `ok` empty and the window mislabeled as a MISMATCH. A
    # plain assignment IS caught by set -e, so an infra blip aborts with the real error instead of a false fidelity fail.
    if (( $# > 3 )); then
        compare_out="$4"
    else
        compare_out="$(compare_window "$LO" "$HI")"
    fi
    read -r src_rows dst_rows src_checksum dst_checksum ok <<< "$compare_out"
    checked=$((checked + 1))
    if [[ "$src_rows" =~ ^[0-9]+$ && "$dst_rows" =~ ^[0-9]+$ ]]; then
        rows_compared=$(( rows_compared + src_rows + dst_rows ))
        (( src_rows + dst_rows > 0 )) || empty_windows=$(( empty_windows + 1 ))
    fi
    if [[ "$ok" == "1" ]]; then
        log "$label ($LO .. $HI): OK (rows=$src_rows)"
        return 0
    fi
    window_certified=0   # set only by the artifact verdict, which differs but passes; gates the drill-down nudge
    # The re-checks below are the expensive part of a differing window and the gate is slowest exactly when it finds
    # something, so say what they cost before issuing them: confirm-keys and version-ties each re-read the window.
    recheck_bytes="$(estimate_window_bytes "$LO" "$HI" | awk '{ printf "%.0f", 2 * ($1 + $2) }')" || recheck_bytes=""
    if [[ "$recheck_bytes" =~ ^[0-9]+$ ]]; then
        DONE_EST_BYTES=$(( DONE_EST_BYTES + recheck_bytes ))
        log "  resolving $label: the re-checks read ~$(human_bytes "$recheck_bytes"), ETA" \
            "~$(human_duration "$(eta_seconds "$recheck_bytes")") at $EFFECTIVE_MBPS MB/s." >&2
    fi
    # A window difference is not yet a fidelity failure: windowing on created_at under FINAL can surface a
    # SUPERSEDED version on one side only. Re-check the differing keys on the sorting key, where FINAL
    # always sees every version, and let that decide. Plain assignment so set -e catches an infra blip
    # instead of an empty result being read as "artifact" and silently passing a real mismatch.
    unresolved="$(confirm_keys_window "$LO" "$HI")"
    [[ "$unresolved" =~ ^[0-9]+$ ]] || {
        log "FAILED $label: confirm-keys returned '$unresolved' (expected a count) — treating as a real mismatch." >&2
        unresolved=-1
    }
    if [[ "$unresolved" == "0" ]]; then
        # The artifact reading holds only where FINAL had a forced winner for every key. Ask now, where it decides
        # the verdict, rather than on every differing window.
        # Read failure feeds the UNCERTIFIABLE branch below rather than aborting: that verdict exists for exactly
        # this case, and a mid-loop abort would lose the remaining windows and the summary. Unlike confirm-keys
        # above, where an infra blip is meant to stop the run before any verdict is drawn.
        ties_out="$(version_ties_window "$LO" "$HI")" || ties_out=""
        read -r src_ties dst_ties <<< "$ties_out"
        # Output that is not two counts cannot certify the window, but it is not a tie either: encoding it as one
        # would print a tie diagnosis, and a triage procedure, for what is a client or infrastructure failure.
        if ! [[ "$src_ties" =~ ^[0-9]+$ && "$dst_ties" =~ ^[0-9]+$ ]]; then
            uncertifiable=$((uncertifiable + 1))
            log "UNCERTIFIABLE $label ($LO .. $HI): version-ties returned '$ties_out', expected two counts." >&2
            log "  The window differs and its re-check found nothing genuinely differing, but whether that verdict is" >&2
            log "  decidable could not be established. This is a read failure, not a version tie." >&2
        elif (( src_ties == 0 && dst_ties == 0 )); then
            artifacts=$((artifacts + 1))
            window_certified=1
            log "$label ($LO .. $HI): OK — superseded-version artifact (src_rows=$src_rows dst_rows=$dst_rows); every differing key's live row is identical on both sides, and no key's newest version is tied"
        else
            inconclusive=$((inconclusive + 1))
            log "INCONCLUSIVE $label ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows version_ties=src:$src_ties/dst:$dst_ties" >&2
            log "  Every differing key's live row matched, but this window holds keys whose newest last_updated_at is" >&2
            log "  carried by MORE THAN ONE DISTINCT ROW, so FINAL chose between rows that actually differ and may have" >&2
            log "  landed on the same one on both sides by luck — including where one side is missing a version." >&2
            log "  ON SPANS THERE ARE TWO CAUSES, and a non-zero src_ties with dst_ties=0 points at the second: the same" >&2
            log "  span at one last_updated_at under TWO parent_span_id values. The source keeps those as separate" >&2
            log "  physical rows (parent_span_id is in its sort key and is mutable); the successor collapses them and" >&2
            log "  picks arbitrarily. Resolve it from the source's version set per the runbook's triage." >&2
            log "  NOT certified either way." >&2
        fi
    else
        mismatches=$((mismatches + 1))
        log "MISMATCH $label ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows src_checksum=$src_checksum dst_checksum=$dst_checksum genuinely_differing_keys=$unresolved" >&2
        # Ask the tie detector HERE too, not only on the confirm-keys == 0 branch. A tie leaves each side's winner
        # arbitrary, and the two directions land in different branches: when the picks COINCIDE the window reaches
        # confirm-keys == 0 and the block above decides it, but when they DIFFER the key is counted in
        # genuinely_differing_keys and lands here — which is where the spans-only tie (one span id, two
        # parent_span_id values, one last_updated_at) actually shows up. Without this the counts the runbook tells an
        # operator to read are never printed for the case that produces them.
        #
        # The verdict stays MISMATCH. A tie EXPLAINS differing keys, it does not clear them, and the counts are a
        # window-wide upper bound (see the version-ties block) rather than a per-key attribution — so downgrading to
        # INCONCLUSIVE here would let one unrelated tie relabel a genuinely broken window as merely undecidable.
        # Both exit non-zero either way; what this adds is the diagnosis, not a different gate.
        ties_out="$(version_ties_window "$LO" "$HI")" || ties_out=""
        read -r src_ties dst_ties <<< "$ties_out"
        if ! [[ "$src_ties" =~ ^[0-9]+$ && "$dst_ties" =~ ^[0-9]+$ ]]; then
            log "  version_ties=UNREAD (the tie check returned '$ties_out', expected two counts). The MISMATCH above" >&2
            log "  stands on its own; what is unknown is whether a tie accounts for any of it." >&2
        elif (( src_ties == 0 && dst_ties == 0 )); then
            log "  version_ties=src:0/dst:0 — no key in this window has a tied newest version, so every differing key" >&2
            log "  above is a REAL difference. This is the shape that means a genuine fidelity failure." >&2
        else
            log "  version_ties=src:$src_ties/dst:$dst_ties — this window holds keys whose newest last_updated_at is" >&2
            log "  carried by MORE THAN ONE DISTINCT row, so FINAL and argMax each chose arbitrarily and may have" >&2
            log "  landed on different rows per side. A tie can therefore account for some or all of the differing" >&2
            log "  keys above WITHOUT the copy being wrong — but the count is an upper bound over the whole window," >&2
            log "  not a per-key attribution, so it cannot clear them: resolve each differing key from both sides'" >&2
            log "  version sets, per the runbook's triage." >&2
            if (( src_ties > 0 && dst_ties == 0 )); then
                log "  src:N/dst:0 is the SPANS-ONLY cause: the same span at one last_updated_at under two" >&2
                log "  parent_span_id values. The source keeps both (parent_span_id is in its sort key and is" >&2
                log "  mutable); the successor collapses them to one key and picks arbitrarily. Re-copying does not" >&2
                log "  break the tie." >&2
            fi
        fi
    fi
    if [[ "$DRILL_DOWN" == "1" ]]; then
        log "  differing keys (key, src_hash, dst_hash; NULL = missing on that side):" >&2
        # Non-fatal on purpose: the verdict for this window is already decided above, and since --drill-down now
        # runs on artifact windows too, an unguarded failure here under set -e would abort a run that was passing.
        drill_down_window "$LO" "$HI" >&2 || log "  drill-down failed for $label; the verdict above stands" >&2
    elif (( window_certified == 0 )); then
        # Only suggested where there is something to investigate. An artifact window differs but passes, so nudging
        # an operator to drill into it would read as an unresolved problem on a clean run.
        log "  re-run with --drill-down to list the differing keys for this window" >&2
    fi
}

if (( WINDOW_MODE == 1 )); then
    compare_one_window window "$WINDOW_FROM" "$WINDOW_TO"
    DONE_EST_BYTES=$(( DONE_EST_BYTES + QUERY_EST[0] ))
elif (( SINGLE_PASS == 0 )); then
    for i in "${!QUERY_LO[@]}"; do
        compare_one_window "week ${QUERY_FIRST_WEEK[$i]}" "${QUERY_LO[$i]}" "${QUERY_HI[$i]}"
        DONE_EST_BYTES=$(( DONE_EST_BYTES + QUERY_EST[i] ))
    done
else
    for i in "${!QUERY_LO[@]}"; do
        first="${QUERY_FIRST_WEEK[$i]}"
        last="${QUERY_LAST_WEEK[$i]}"
        # Plain assignment, for the same set -e reason as compare_window's call site.
        batch_out="$(compare_weeks "${QUERY_LO[$i]}" "${QUERY_HI[$i]}")"
        DONE_EST_BYTES=$(( DONE_EST_BYTES + QUERY_EST[i] ))
        # Every row must be a selected week of this batch, once, with six integer fields. Anything else is not a
        # verdict, and a week dropped, misread or overwritten here would otherwise print as a wrong verdict. The loop
        # reads a captured string, so it ends at its last line; the blank line skipped is the one an empty result
        # leaves. The week is checked lexically before the arithmetic below, as the bounds were at startup.
        VERDICTS=()
        while IFS=$'\t' read -r week src_rows dst_rows src_checksum dst_checksum ok; do
            [[ -n "$week" ]] || continue
            if ! [[ "$week" =~ ^(0|[1-9][0-9]{0,8})$ && "$src_rows" =~ ^[0-9]+$ && "$dst_rows" =~ ^[0-9]+$ \
                    && "$src_checksum" =~ ^[0-9]+$ && "$dst_checksum" =~ ^[0-9]+$ && "$ok" =~ ^[01]$ ]] \
                    || (( week < first || week > last || (week - FROM_WEEK) % WEEKS_STRIDE != 0 )) \
                    || [[ -n "${VERDICTS[week]+set}" ]]; then
                log "ERROR: compare-weeks returned a row that is not a verdict for a selected week of [$first..$last]:" >&2
                log "       '$week $src_rows $dst_rows $src_checksum $dst_checksum $ok'. Refusing to draw verdicts from it." >&2
                exit 2
            fi
            VERDICTS[week]="$src_rows $dst_rows $src_checksum $dst_checksum $ok"
        done <<< "$batch_out"
        # A selected week with no row holds no data on either side, which is what compare reports as 0 0 0 0 1.
        for (( week=first; week<=last; week+=WEEKS_STRIDE )); do
            compare_one_window "week $week" "$(week_start "$week")" "$(week_start $(( week + 1 )))" \
                "${VERDICTS[week]:-0 0 0 0 1}"
        done
    done
fi

# STATING THE COVERAGE IS NOT THE SAME AS DISQUALIFYING THE RUN. The runbook's Go/No-Go requires the pre-EXCHANGE gate
# to be a FULL weekly compare, because sampling, a stride or a narrowed week range can miss a single-row deletion leak
# and still report ok=1 — and the verdict line is what an operator quotes into that checklist, with the coverage trailing
# behind it. So a weekly run that is not full says so in the verdict itself, PASSED or FAILED: under live updates the
# gate normally ends in a triaged MISMATCH, so the FAILED lines need the label as much as the PASSED one. Judged on the
# values, not on which flags were passed: an explicit --weeks-stride 1 or a --to-week at the last week is still a full
# compare. A run that starts past week 0 on a PINNED anchor is a resumption and says so instead, naming the weeks it
# must be paired with.
# Window mode is a different tool (the post-swap reconciled range) and prints its own explicit bounds, so it is not
# labelled against a gate it was never meant to satisfy.
PARTIAL=""
if (( WINDOW_MODE != 1 )); then
    if (( COVERAGE != 100 || WEEKS_STRIDE != 1 || TO_WEEK != LAST_WEEK )) \
        || { (( FROM_WEEK > 0 )) && [[ -z "$ANCHOR_PIN" ]]; }; then
        PARTIAL=" (PARTIAL — NOT valid as the pre-EXCHANGE gate)"
    elif (( FROM_WEEK > 0 )); then
        PARTIAL=" (RESUMED at week $FROM_WEEK on anchor $ANCHOR — counts toward the pre-EXCHANGE gate only together with"
        PARTIAL+=" a run on this anchor that verified weeks [0..$(( FROM_WEEK - 1 ))])"
    fi
fi
# Fidelity mismatch is the hard failure, and so is a window the re-check could not decide (both exit 1).
if (( mismatches != 0 || inconclusive != 0 || uncertifiable != 0 )); then
    (( mismatches == 0 )) || log "FAILED$PARTIAL: $mismatches of $checked windows mismatched." >&2
    if (( uncertifiable != 0 )); then
        log "FAILED$PARTIAL: $uncertifiable of $checked windows could not be read to completion — the tie check did not return" >&2
        log "        counts, so those windows are neither certified nor shown to differ. Re-run them." >&2
    fi
    if (( inconclusive != 0 )); then
        log "FAILED$PARTIAL: $inconclusive of $checked windows could not be certified — a version tie left FINAL's choice" >&2
        log "        arbitrary on at least one side, so the re-check's 'no genuine difference' cannot be relied on." >&2
        log "        Triage those windows per the runbook. This is neither a mismatch nor a pass." >&2
    fi
    exit 1
fi
if [[ "$checked" == "0" ]]; then
    # An empty range compared nothing, so "all windows match" would be vacuously true — the one answer a fidelity gate
    # must never give. Fail instead: reaching here means the bounds excluded every week, not that the data agrees.
    # Unreachable in window mode, where the from < to check up front guarantees exactly one window; kept unconditional
    # so the guard does not depend on that argument staying true.
    log "FAILED: no window was compared — weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE selects nothing." >&2
    log "        Nothing was verified, so this is NOT a pass. Widen the range (--from-week/--to-week)." >&2
    exit 1
fi
if (( rows_compared == 0 )); then
    # Windows were compared, but every one was empty on both sides — "all windows match" would again be vacuous. The
    # realistic way here is window mode over a range where no span was created.
    log "FAILED: every compared window was empty on both sides, so nothing was verified and this is NOT a pass." >&2
    log "        Check the range against where the data sits: min/max(created_at) of '$OLD_TABLE'." >&2
    log "        If the range is genuinely empty, record that, not a PASS." >&2
    exit 1
fi
# The PASSED line states the range it covered, so a pass can never be read as broader than it was — the same reason the
# weekly form prints its bounds and stride. In window mode that is the explicit window, which is the whole point of the
# mode: a reconciliation compare has to be quotable as "this exact range was checked".
if (( WINDOW_MODE == 1 )); then
    # "created in" rather than just the range: 000005 bounds on created_at, so a span created earlier and merely
    # updated inside the window is not in this compare (see --window-from's doc). Saying so on the PASSED line is what
    # stops the pass being quoted as broader than it is — reconcile.sh's stale_keys / payload_mismatch_keys are what
    # cover the updated ones.
    COVERED="spans CREATED in window [$WINDOW_FROM .. $WINDOW_TO) UTC, $SAMPLE_LABEL"
else
    COVERED="weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE, $SAMPLE_LABEL"
fi
if (( COVERAGE < 100 && empty_windows > 0 )); then
    log "Coverage: $empty_windows of $checked windows held no row inside the coverage slices on either side."
fi
if [[ "$artifacts" != "0" ]]; then
    log "PASSED$PARTIAL: all $checked windows match ($COVERED); $artifacts window(s) held a superseded-version"
    log "        artifact only — a key written more than once lands its stale version in an earlier created_at week on"
    log "        one side. Live data is identical on both sides and no key in those windows had a tied newest version,"
    log "        so the re-check was decisive; nothing to fix (see the confirm-keys block)."
else
    log "PASSED$PARTIAL: all $checked windows match ($COVERED)."
fi
