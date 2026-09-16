#!/usr/bin/env bash
#
# Backfill driver for the spans cutover (runbook: ../README.md, step 1).
#
# Copies spans -> spans_local_v2 oldest to newest, reconciling and aborting on divergence. It iterates by week (for
# progress and --from-week resume), but each week is further split, adaptively, into time sub-windows so that no single
# INSERT moves more than --max-rows-per-insert rows. On a table this size a whole week is enormous; bounding
# each statement keeps its duration, its blast radius on failure, and the destination part-count it creates all in
# check. Memory is separately bounded by ClickHouse's block squashing — see --min-insert-block-size-bytes, which on
# this table is the only dial over peak insert memory.
#
# PARTITION SPREAD, AND THE ONE SETTING THAT MATTERS. The destination is weekly-partitioned on the honest Monday of
# id_at, so a created_at window contains rows landing in many destination partitions: far-future ids (litellm
# BerriAI/litellm#31294 mints ~2201) spread across a long stretch of weeks, and non-v7 ids collapse into the epoch
# week. ClickHouse caps partitions per insert block at 100 by default and, with
# throw_on_max_partitions_per_insert_block = 1, ABORTS the INSERT rather than degrading -- which is what the traces
# cutover hit. Raising --max-partitions-per-insert-block above the destination's total distinct partition count is the
# whole fix, and it is the same fix traces applied: the setting is a LIMIT CHECK, not an allocation, so it costs
# nothing when a block touches few partitions.
#
# An earlier revision of this driver split each window into two complementary statements to keep the ordinary rows off
# the high cap. Measurement retired it: min_insert_block_size_bytes caps a block in BYTES, so at spans' row width a
# block holds a few thousand rows -- and a block can never span more partitions than it holds rows. The split cost a
# second statement per window and produced neither lower peak memory nor fewer parts than simply choosing the byte
# bound. README "Partition spread, and the one setting that matters" carries the numbers.
#
# Week boundaries are derived from the data (toMonday(min/max(created_at))) — the operator does not hand-write dates.
# Idempotent and resumable: a window whose destination count already matches the source is skipped.
#
# The backfill INSERTs are NOT duplicated here: each is read from a marked block of
# db-app-analytics/000001_backfill_spans_local_v2.sql (the single source), with the ${...} placeholders substituted per
# window. See README "How backfill.sh and 000001 relate", "Why slice by created_at", and "Batching and throttling".
#
# Usage:
#   CLICKHOUSE_HOST=... CLICKHOUSE_PASSWORD=... ./backfill.sh --database opik [options]
#
# Connection: CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment, plus --host and --port. CLICKHOUSE_PORT is
# NOT honored by clickhouse-client, and CLICKHOUSE_HOST is honored only when no connection flag is given, so pass
# --host and --port together. The user must be able to set `log_comment` (used for cutover attribution in
# query_log): a `readonly = 1` profile rejects it outright ("Cannot modify 'log_comment' setting in readonly mode"),
# so a read-only assessor needs `readonly = 2` and the migration user needs a non-readonly profile.
# --database is required.
#
# Options:
#   --database NAME           analytics database (e.g. opik). Required.
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
#   --dry-run                 print the window plan and per-window source counts; do not INSERT.
#   --from-week N             start at week offset N (0-based from the anchor Monday). Default 0.
#   --to-week M               stop after week offset M (inclusive). Default: last week with data.
#   --max-rows-per-insert R   upper bound on rows per created_at window; a week over this is halved by time until each
#                             sub-window fits. Default 1500000. Derived rather than inherited: traces used 2,000,000 at
#                             its own bytes/row, to bound the bytes read per statement; spans rows are larger on disk,
#                             so the same byte budget implies fewer rows. Smaller = safer per statement but more parts /
#                             merge pressure; larger = fewer parts but a bigger blast radius. This is a per-window
#                             bound, not a memory bound — --min-insert-block-size-bytes is the memory bound.
#   --max-partitions-per-insert-block N
#                             partitions one insert block may span (SETTINGS max_partitions_per_insert_block).
#                             Default 20000; 0 = unlimited. A LIMIT CHECK, not an allocation: ClickHouse buffers
#                             ~34 KiB per partition a block ACTUALLY touches, so a generous value costs nothing.
#                             Set it ABOVE estimate.sh audit 1's `total_partitions`, which is a HARD upper bound —
#                             a block cannot span more partitions than the table has. 20000 also clears the SCHEMA's
#                             own ceiling (UUIDv7ToDateTime is honest from the epoch to 2299, ~17,000 weekly
#                             partitions), so it cannot be too low for any estate; confirm against audit 1 rather
#                             than assuming. ClickHouse's own default is 100 and, with
#                             throw_on_max_partitions_per_insert_block = 1, it ABORTS the INSERT rather than degrading.
#   --max-insert-block-size N rows per part-forming block (SETTINGS max_insert_block_size). Default 1048576, the
#                             ClickHouse default. On spans this cap rarely binds — rows are wide uncompressed, so the
#                             byte bound below fires first — which is why it is left stock.
#   --min-insert-block-size-bytes N
#                             bytes per part-forming block (SETTINGS min_insert_block_size_bytes). Default 268435456
#                             (256 MiB, the ClickHouse default, and what the traces backfill ran at).
#
#                             THIS IS THE ONE DIAL OVER PEAK INSERT MEMORY, and it trades against part count. It caps
#                             the block in bytes, so it also bounds how many partitions a block can span (a block
#                             cannot span more partitions than it holds rows). Measured on spans-shaped rows,
#                             200k rows at ~50 KB/row uncompressed:
#
#                               256 MiB  ->  ~1.1 GiB peak,  fewest parts written
#                                32 MiB  ->  ~315 MiB peak,  ~5x the parts
#
#                             Lower it on a memory-constrained data node and accept the merge pressure; raise it to
#                             cut part count if the node has the headroom. Measure yours from system.query_log's
#                             memory_usage, filtered by the log_comment this driver sets.
#   --max-insert-threads N    threads for the INSERT SELECT pipeline (SETTINGS max_insert_threads).
#                             OMITTED BY DEFAULT, and omitted means INHERIT: the setting line is stripped
#                             from the SQL, so whatever the server profile sets applies. This matters --
#                             rendering an explicit 0 would OVERRIDE a profile that sets it and force the
#                             insert serial, which is a silent slowdown rather than a no-op. ClickHouse
#                             Cloud ships non-zero defaults (1/2/4 by node memory), and a self-managed
#                             cluster may set it in a profile too.
#                             Pass an explicit 0 to FORCE "INSERT SELECT no parallel execution"; pass N to
#                             request N. Where nothing sets it, ClickHouse's own default is 0, so the
#                             insert side runs single-threaded unless raised.
#
#                             PRECONDITION, per upstream: "Parallel INSERT SELECT has effect only if
#                             the SELECT part is executed in parallel" (see max_threads). If the read
#                             side is serialised, raising this buys nothing.
#
#                             WHY THE INSERT SIDE MAY OR MAY NOT BE THE CONSTRAINT HERE, and why that differs from
#                             traces. The traces destination materialised `output_keys`, which PARSES the output JSON
#                             per row, and profiling put the insert side at the bottleneck because of it. THERE IS NO
#                             output_keys ON SPANS -- it is a traces-only column (migration 000044) and 000115 records
#                             that spans has no counterpart -- so that particular cost is absent. What spans does
#                             materialise is truncated_input / truncated_output (substring copies of documents that can
#                             be very large), the three *_length counters, duration and id_at. Do NOT carry the traces
#                             conclusion over: measure it here.
#
#                             HOW TO CONFIRM IT: effective cores sit near 1 while the machine is
#                             otherwise idle and OSIOWaitMicroseconds is 0 -- i.e. the copy is neither
#                             CPU-saturated nor I/O bound, it is serialised. Compute effective cores
#                             from query_log, MINDING THE UNITS -- the ProfileEvents are MICROseconds
#                             and query_duration_ms is MILLIseconds, so the *1000 is not optional:
#                               (UserTimeMicroseconds + SystemTimeMicroseconds) / (query_duration_ms * 1000)
#                             Without it the result is 1000x too high and will read as hundreds of
#                             cores. Sanity-check against the node's core count: a value above it means
#                             the arithmetic is wrong, not that the machine is busy.
#
#                             NOTE WHAT THIS MEASURES: query_log aggregates are QUERY-WIDE CPU. They do
#                             not separate read-pipeline threads from insert-pipeline threads, so this
#                             number cannot by itself attribute the CPU to the sink. What makes it
#                             evidence is the DELTA: raise the setting and effective cores rise towards
#                             the thread count while the read side is unchanged.
#
#                             COSTS, BOTH OF THEM.
#                             (1) MEMORY. Upstream is explicit: "Higher values will lead to higher
#                                 memory usage." Each thread carries its own block, so peak memory scales
#                                 with threads x --min-insert-block-size-bytes. Raise this and
#                                 max_memory_usage together, or narrow the window, rather than raising
#                                 threads alone into a fixed ceiling.
#                             (2) PARTS. Each insert thread writes its own parts, so part count per
#                                 partition grows -- on top of the one part per partition each block
#                                 already writes. Watch it against THIS cluster's parts_to_throw_insert
#                                 and parts_to_delay_insert, read from system.merge_tree_settings. Do NOT
#                                 work from a remembered default: ClickHouse has changed these across
#                                 versions, and a deployment may tune them further, so a hardcoded ratio can
#                                 be an order of magnitude wrong in either direction.
#
#                             CHOOSING A VALUE IS A CAPACITY DECISION, NOT A BENCHMARK. On an idle
#                             rehearsal environment a large value looks free; on a production cluster
#                             those threads compete with live query latency. Pick the share of cores
#                             the cutover may take while serving traffic, and validate the value you
#                             will actually deploy rather than the largest one that fits. Omitting the
#                             flag is the default and means INHERIT. An explicit 0 is not the same as
#                             omitting it: it forces no parallel execution and overrides the server's value.
#   --divergence P            max tolerated |src-dst|/src per window before aborting. Default 0.0001 (0.01%). Kept at
#                             the traces value after re-derivation rather than in spite of it: at this driver's
#                             1,500,000-row window bound it allows 150 rows of in-flight slack, the same order as
#                             traces' 200 at its 2,000,000, and the counts it compares are dedup-aware on BOTH sides
#                             (see count_src_uniq), so the source's wider sort key does not inflate the divergence.
#   --pause-seconds S         sleep S seconds after each inserted window, to let destination merges catch up and bound
#                             the part count / IO pressure. Default 0. Recommended 60-120 on spans, against traces'
#                             30-60: the destination accumulates an order of magnitude more partitions, so the merge
#                             backlog a window leaves behind is spread over far more part sets. Size it from the
#                             observed system.merges backlog on prod-test rather than from this note.
#   --min-free-factor F       abort at startup unless node free disk >= F x the PROJECTED DESTINATION size (see
#                             --dest-compression-ratio). Default 1.35. Pass 0 to skip the check.
#                             THIS IS THE THRESHOLD THE TRACES RUNBOOK COULD NOT LEND US. Its gate multiplied the
#                             SOURCE size by 2.0 — deliberately conservative, because a driver cannot know the
#                             destination's compression — and on spans it can demand more than the whole volume:
#                             unsatisfiable, and unsatisfiable even at factor 1.0. So the spans gate sizes from an
#                             explicit destination projection and the factor covers MERGE SCRATCH on top of the
#                             finished copy rather than a whole second copy of the source. 1.35 is ~35% headroom above
#                             the projection rather than the source. The margin can still be thin, which is why
#                             estimate.sh warns below 3 TiB of it and why the runbook names retiring
#                             `traces_pre_cutover_backup` (OPIK-8263) as the lever.
#                             This is a whole-node floor; on tiered storage validate per-volume (hot) headroom too.
#   --dest-compression-ratio C
#                             the destination's projected on-disk size as a fraction of the source's. Default 0.381,
#                             measured during OPIK-7400 on the 000116 codec set. Replace it with a value measured on
#                             your own estate, on prod-test, before the production window — the runbook's acceptance
#                             criterion 2 requires exactly that. A
#                             value above 1.0 is refused: the destination cannot be larger than the source under any
#                             codec set the DDL permits, so that is a typo rather than a choice.
#   --confirm-tiered-headroom  REQUIRED when the destination storage_policy is tiered (multi-volume) or differs from the
#                             source's. The whole-node --min-free-factor check cannot see per-volume headroom, and new
#                             parts land on the hot volume before they tier; this asserts the operator validated hot
#                             headroom out of band. (No effect on a single-volume/default policy.)
#   --state-file PATH         file the captured backfill_start is written to and reused from, stored with an explicit
#                             ' UTC' marker and only accepted with it — step 2 parses the anchor as UTC, so one captured
#                             in another zone would shift it silently. On resume the ORIGINAL
#                             anchor is kept; re-minting a later one would miss deletes that fired during the first run
#                             against already-copied rows. Default ./spans_cutover_backfill_start — note this is
#                             CWD-RELATIVE, so resuming from a different directory would not find it; pass an absolute
#                             path for a multi-session cutover, which on spans is the expected case rather than the
#                             exception. If the anchor is missing while the destination already holds rows, the script
#                             ABORTS rather than mint a later one (that would leak deletes).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKFILL_SQL="$SCRIPT_DIR/db-app-analytics/000001_backfill_spans_local_v2.sql"

# Fixed source/destination of this migration. The backfill INSERTs themselves live in 000001; these are only for the
# script's own sizing and reconciliation queries.
SRC_TABLE="spans"
DST_TABLE="spans_local_v2"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
DRY_RUN=0
FROM_WEEK=0
TO_WEEK=""
MAX_ROWS=1500000          # rows: per-window bound; a week over this is halved in time until each window fits.
MAX_PARTITIONS=20000      # partitions one insert block may span. 0 = unlimited. A limit check, not an allocation.
MAX_INSERT_BLOCK_SIZE=1048576     # rows per part-forming block (the ClickHouse default; rarely the binding one here).
MIN_INSERT_BLOCK_BYTES=268435456  # bytes per part-forming block (256 MiB) — the one dial over peak insert memory.
MAX_INSERT_THREADS=""     # threads: SETTINGS max_insert_threads. EMPTY = inherit the server's value (the line is
                          # stripped from the SQL). An explicit 0 FORCES no parallel INSERT SELECT execution.
DIVERGENCE="0.0001"       # fraction: max tolerated |src-dst|/src per settled window before aborting (0.01%).
PAUSE_SECONDS=0           # seconds: sleep after each inserted window so destination merges catch up. 60-120 on spans.
MIN_FREE_FACTOR="1.35"    # multiple of the PROJECTED DESTINATION size node free space must clear before starting.
DEST_COMPRESSION_RATIO="0.381"  # destination on-disk size as a fraction of the source's (measured in OPIK-7400).
STATE_FILE="./spans_cutover_backfill_start"  # backfill_start is persisted here and reused on resume (one anchor).
CONFIRM_TIERED_HEADROOM=0 # required when the destination storage_policy is tiered/mismatched (see preflight_capacity).

# Floor on adaptive splitting: never divide a window shorter than this. Guards against splitting forever on a single
# hot instant; such a window is inserted whole (memory is still bounded by block squashing).
MIN_WINDOW_SECONDS=60

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
        --dry-run) DRY_RUN=1; shift ;;
        --from-week) FROM_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --to-week) TO_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-rows-per-insert) MAX_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --min-insert-block-size-bytes) MIN_INSERT_BLOCK_BYTES="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --divergence) DIVERGENCE="${2:?"$1 requires a value"}"; shift 2 ;;
        --pause-seconds) PAUSE_SECONDS="${2:?"$1 requires a value"}"; shift 2 ;;
        --min-free-factor) MIN_FREE_FACTOR="${2:?"$1 requires a value"}"; shift 2 ;;
        --dest-compression-ratio) DEST_COMPRESSION_RATIO="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-tiered-headroom) CONFIRM_TIERED_HEADROOM=1; shift ;;
        --state-file) STATE_FILE="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2
           echo "Run '$(basename "$0") --help' for the options this driver accepts." >&2
           exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }

# One place for the connection and client-side options, so every call site below carries the same host, port,
# database, log_comment and receive_timeout, and cannot drift from the others.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT" --log_comment 'spans_local_v2_cutover:backfill')
# --state-file is an operator-owned path read with cat and written with > (both quoted); reject a blank or multi-line
# value so the single-line anchor round-trips cleanly.
[[ -n "$STATE_FILE" && "$STATE_FILE" != *$'\n'* ]] || { echo "ERROR: --state-file must be a non-empty single-line path." >&2; exit 2; }
# Numeric args flow into the reference SQL / window arithmetic; require sane numeric shapes so none can alter the query.
[[ "$MAX_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-rows-per-insert must be a positive integer." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
[[ "$MIN_INSERT_BLOCK_BYTES" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --min-insert-block-size-bytes must be a positive integer." >&2; exit 2; }
# 0 is meaningful here (ClickHouse reads it as "unlimited"), so allow it — unlike the bounds above. Upper-bounded at 6
# digits: the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be
# rendered into the SQL and rejected by the server on the first INSERT — after the capacity preflight has passed and the
# backfill_start anchor has been minted, which is a far more expensive place to discover a typo.
[[ "$MAX_PARTITIONS" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
# 0 is meaningful (ClickHouse default, no parallel INSERT SELECT execution), so allow it. Bounded at 2 digits: this is
# a share of cores, and a value beyond the machine's core count buys nothing while multiplying parts.
[[ -z "$MAX_INSERT_THREADS" || "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (force no parallel INSERT SELECT execution) or 1..99; omit it entirely to inherit the server's setting." >&2; exit 2; }
[[ "$FROM_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --from-week must be a non-negative integer." >&2; exit 2; }
[[ -z "$TO_WEEK" || "$TO_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --to-week must be a non-negative integer." >&2; exit 2; }
[[ "$PAUSE_SECONDS" =~ ^[0-9]+$ ]] || { echo "ERROR: --pause-seconds must be a non-negative integer." >&2; exit 2; }
[[ "$DIVERGENCE" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --divergence must be a number." >&2; exit 2; }
[[ "$MIN_FREE_FACTOR" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --min-free-factor must be a number." >&2; exit 2; }
[[ "$DEST_COMPRESSION_RATIO" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --dest-compression-ratio must be a number." >&2; exit 2; }
# A ratio above 1.0 would make the gate LOOSER than sizing from the source, which is the one direction this parameter
# must never move: it exists to make a conservative gate satisfiable, not to make a satisfiable gate optimistic.
[[ "$(awk -v r="$DEST_COMPRESSION_RATIO" 'BEGIN { print (r > 0 && r <= 1.0) ? 1 : 0 }')" == "1" ]] \
    || { echo "ERROR: --dest-compression-ratio must be in (0, 1.0]. The destination cannot be larger than the source under the 000115/000116 codec set." >&2; exit 2; }
[[ -f "$BACKFILL_SQL" ]] || { echo "ERROR: cannot find backfill SQL at $BACKFILL_SQL" >&2; exit 2; }

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block (exact-line markers) from the reference SQL.
extract() {
    awk -v begin="-- >>> BEGIN $1" -v end="-- >>> END $1" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$BACKFILL_SQL"
}

# Refuse rendered SQL that is not what the caller asked for. A marker renamed, moved, indented or split yields text
# that is empty or only the block's own comments, and clickhouse-client exits 0 on either, so `set -e` never fires and
# the step prints its success line having done nothing.
#
# KEEP IN STEP WITH exchange_and_wrap.sh / reconcile.sh's require_rendered: the checks, and their ORDER, are the same
# validation contract -- exactly one BEGIN and one END, the END after its BEGIN, executable SQL after comments are
# stripped, the caller's identity token present, and no surviving ${...} placeholder. This copy EXITS rather than
# returning, because its call sites are plain assignments under `set -e` inside a command substitution.
require_rendered() {
    local sql="$1" what="$2" must_contain="$3" masked begins ends begin_line end_line
    begins="$(grep -cxF -e "-- >>> BEGIN $what" "$BACKFILL_SQL" || true)"
    ends="$(grep -cxF -e "-- >>> END $what" "$BACKFILL_SQL" || true)"
    if (( begins != 1 || ends != 1 )); then
        echo "ERROR: $BACKFILL_SQL holds $begins '-- >>> BEGIN $what' and $ends '-- >>> END $what'; expected one of each." >&2
        exit 2
    fi
    read -r begin_line end_line <<<"$(awk -v b="-- >>> BEGIN $what" -v e="-- >>> END $what" \
        '$0 == b {bl = NR} $0 == e {el = NR} END {print bl, el}' "$BACKFILL_SQL")"
    if (( end_line <= begin_line )); then
        echo "ERROR: $BACKFILL_SQL has the '$what' markers out of order (BEGIN at line $begin_line, END at line" >&2
        echo "       $end_line), so the block would capture to end of file and sweep every later block into it." >&2
        exit 2
    fi
    masked="$(sed 's/--.*$//' <<<"$sql")"
    if [[ -z "${masked//[[:space:]]/}" ]]; then
        echo "ERROR: the '$what' block from $BACKFILL_SQL rendered no executable SQL (empty, or comments only)." >&2
        exit 2
    fi
    if ! grep -qF "$must_contain" <<<"$masked"; then
        echo "ERROR: the '$what' block from $BACKFILL_SQL has no '$must_contain' outside its comments, so the markers" >&2
        echo "       are around the wrong statement. Refusing to run it." >&2
        exit 2
    fi
    if grep -qF '${' <<<"$masked"; then
        echo "ERROR: the '$what' block from $BACKFILL_SQL still holds an unsubstituted \${...} placeholder after" >&2
        echo "       rendering. Refusing to send SQL containing a literal placeholder." >&2
        exit 2
    fi
}

# Blank out SQL comments while preserving line numbering: `--` to end of line, and /* */ which may span lines.
# Used by every max_insert_threads check so that "is this assignment real?" means "is it executable?" rather than
# "does this text appear anywhere?". Without it a line-anchored match inside a block comment counts as the
# assignment (so an explicit value renders into a comment and silently does not apply), and a mere mention of the
# placeholder in a comment trips the post-condition (so a perfectly good file is refused).
mit_mask_comments() {
    awk '{
        line = $0; out = ""; i = 1; n = length(line)
        while (i <= n) {
            if (inblk) {
                if (substr(line, i, 2) == "*/") { inblk = 0; i += 2 } else { i++ }
            } else {
                if (substr(line, i, 2) == "/*") { inblk = 1; i += 2 }
                else if (substr(line, i, 2) == "--") { break }
                else { out = out substr(line, i, 1); i++ }
            }
        }
        print out
    }' <<<"$1"
}

# Line numbers of EXECUTABLE, isolated `max_insert_threads = ${MAX_INSERT_THREADS},` assignments.
mit_assignment_lines() {
    mit_mask_comments "$1" | grep -nE '^[[:space:]]*max_insert_threads = \$\{MAX_INSERT_THREADS\},[[:space:]]*$' | cut -d: -f1
}

# Abort unless the SQL text holds exactly one such assignment. $2 names the block, for diagnostics.
#
# NOTE THE SCOPE CHANGE FROM THE TRACES DRIVER: it validated the whole file, because the file held one statement. Here
# the file holds three, each with its own SETTINGS clause, so the check runs PER EXTRACTED BLOCK. Validating the file
# as a whole would count three assignments and refuse every run.
mit_require_one_assignment() {
    local n
    n="$(mit_assignment_lines "$1" | grep -c . || true)"
    if [[ "$n" -ne 1 ]]; then
        echo "ERROR: expected exactly ONE executable line holding nothing but" >&2
        echo "       'max_insert_threads = \${MAX_INSERT_THREADS},' in the '$2' block of $BACKFILL_SQL; found $n." >&2
        echo "       The trailing comma is REQUIRED: it is what makes removing the line safe, so the" >&2
        echo "       assignment must not be the last entry in the SETTINGS clause. A line ending in ';'," >&2
        echo "       or in nothing with the ';' on the next line, carries the clause terminator, so" >&2
        echo "       stripping it would leave a dangling comma and no terminator." >&2
        echo "       Occurrences inside '--' or '/* */' comments do not count: a commented assignment" >&2
        echo "       would render the setting into a comment, where it silently does not apply." >&2
        return 1
    fi
    # The assignment must also be the ONLY executable occurrence of the placeholder. Rendering replaces just that
    # one line, so any other executable ${MAX_INSERT_THREADS} survives into the statement -- which the per-window
    # post-condition catches, but only during a real run. Checking it here too is what makes --dry-run a faithful
    # rehearsal: without it a template can pass a full dry-run and abort on the first real window.
    local occurrences
    occurrences="$(mit_mask_comments "$1" | grep -oF '${MAX_INSERT_THREADS}' | grep -c . || true)"
    if [[ "$occurrences" -ne 1 ]]; then
        echo "ERROR: \${MAX_INSERT_THREADS} appears $occurrences times in executable lines of the '$2' block;" >&2
        echo "       expected exactly once, as the SETTINGS assignment. Rendering rewrites only that one line, so any" >&2
        echo "       other executable occurrence would survive into the statement the server receives." >&2
        return 1
    fi
}

# Validate the rendering target ONCE, here, for every block this run will use, rather than only when the first window
# renders. Two reasons, both the same argument this file makes for validating the partition caps eagerly: a malformed
# SETTINGS clause would otherwise pass a full --dry-run clean and abort on the first real window, and in a real run the
# abort would land after the successor-table check, after preflight_capacity() and after backfill_start has been minted
# and persisted. The check reads the file's text, which does not change between windows, so this covers every window;
# the per-window path re-resolves the line and keeps its own post-condition so a mid-run edit is still caught.
mit_require_one_assignment "$(extract backfill)" backfill || exit 2

# Every query runs against the analytics database; --query keeps output scriptable (TSV, no formatting).
ch() {
    clickhouse-client "${CH_ARGS[@]}" --query "$1"
}

log() {
    echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
}


bytes_tib() {
    awk -v b="$1" 'BEGIN { printf "%.2f", b / 1099511627776 }'
}

# Capacity pre-flight, sized from the PROJECTED DESTINATION rather than the source — see --min-free-factor for why the
# traces gate could not be carried over. The whole-node total is a necessary floor, not sufficient on tiered storage:
# new parts land on the hot volume before they tier, so validate per-volume headroom separately. Also warn (not abort)
# if the successor's storage_policy differs from the source's — a mismatch means the copy would not tier the same way
# and could fill the hot volume even when the node total looks fine.
preflight_capacity() {
    local spans_bytes free_bytes dest_bytes need src_policy dst_policy
    spans_bytes="$(ch "SELECT sum(bytes_on_disk) FROM system.parts WHERE database = '$DATABASE' AND table = '$SRC_TABLE' AND active")"
    free_bytes="$(ch "SELECT sum(free_space) FROM system.disks")"
    dest_bytes="$(awk -v s="$spans_bytes" -v r="$DEST_COMPRESSION_RATIO" 'BEGIN { printf "%d", s * r }')"
    log "Capacity: $SRC_TABLE on-disk $(bytes_tib "$spans_bytes") TiB, projected destination $(bytes_tib "$dest_bytes") TiB (x${DEST_COMPRESSION_RATIO}), node free $(bytes_tib "$free_bytes") TiB, need >= ${MIN_FREE_FACTOR}x the projection"
    if [[ "$MIN_FREE_FACTOR" != "0" ]]; then
        need="$(awk -v d="$dest_bytes" -v k="$MIN_FREE_FACTOR" 'BEGIN { printf "%d", d * k }')"
        if [[ "$(awk -v f="$free_bytes" -v n="$need" 'BEGIN { print (f < n) ? 1 : 0 }')" == "1" ]]; then
            log "ABORT: node free disk $(bytes_tib "$free_bytes") TiB is below ${MIN_FREE_FACTOR}x the projected destination ($(bytes_tib "$need") TiB)." >&2
            log "       Free space before starting. The runbook's headroom section names the levers; the largest is" >&2
            log "       retiring 'traces_pre_cutover_backup' (OPIK-8263), which is IRREVERSIBLE and needs its own" >&2
            log "       authorisation. Lowering --min-free-factor or raising --dest-compression-ratio to get past this" >&2
            log "       gate is NOT one of them: both would make the same copy run with less margin, not more room." >&2
            exit 1
        fi
        # Thin-headroom band. Not fatal — the gate passed — but a multi-day copy that also has to absorb merges on a
        # many-thousand-partition destination is exactly where a few hundred GiB of slack disappears without warning.
        if [[ "$(awk -v f="$free_bytes" -v n="$need" 'BEGIN { print (f - n < 3298534883328) ? 1 : 0 }')" == "1" ]]; then
            log "WARNING: only $(bytes_tib "$(awk -v f="$free_bytes" -v n="$need" 'BEGIN { printf "%d", f - n }')") TiB of margin above the requirement (under 3 TiB). Watch free space per volume for the whole copy, and have the headroom lever (see the runbook) authorised before starting rather than mid-run." >&2
        fi
    fi
    src_policy="$(ch "SELECT storage_policy FROM system.tables WHERE database = '$DATABASE' AND name = '$SRC_TABLE'")"
    dst_policy="$(ch "SELECT storage_policy FROM system.tables WHERE database = '$DATABASE' AND name = '$DST_TABLE'")"
    if [[ "$src_policy" != "$dst_policy" ]]; then
        log "WARNING: storage_policy differs ($SRC_TABLE='$src_policy', $DST_TABLE='$dst_policy'). If $SRC_TABLE tiers to cold and $DST_TABLE does not, the whole backfill lands on the hot volume. Confirm this is intended." >&2
    fi
    # Tiered/mismatched storage_policy: the whole-node check above CANNOT see per-volume headroom (new parts land on the
    # hot volume before they tier, so the node total can pass while hot fills mid-backfill — the likeliest prod failure).
    # An accurate hot-headroom check isn't feasible in a preflight (it depends on tiering-vs-write rate), so require an
    # explicit operator acknowledgment that per-volume headroom was validated out of band, rather than proceed silently.
    local dst_volumes
    dst_volumes="$(ch "SELECT uniqExact(volume_name) FROM system.storage_policies WHERE policy_name = '$dst_policy'")"
    dst_volumes="${dst_volumes:-1}"
    if [[ "$dst_volumes" -gt 1 || "$src_policy" != "$dst_policy" ]]; then
        if [[ "$CONFIRM_TIERED_HEADROOM" != "1" ]]; then
            log "ABORT: $DST_TABLE uses a tiered/mismatched storage_policy ('$dst_policy', $dst_volumes volume(s)). The whole-node free-space gate cannot see per-volume headroom — validate the HOT volume has room for the backfill out of band, then re-run with --confirm-tiered-headroom." >&2
            exit 1
        fi
        log "Tiered/mismatched storage_policy acknowledged via --confirm-tiered-headroom (hot-volume headroom validated out of band)."
    fi
}

# Live source rows in [lo, hi). count() honors the deleted-row mask, so masked rows are excluded (they must not copy).
# This is a PHYSICAL row count, used only to size sub-windows against --max-rows-per-insert (not for reconciliation).
count_src() {
    ch "SELECT count()
        FROM $SRC_TABLE
        WHERE created_at >= toDateTime64('$1', 9, 'UTC')
          AND created_at <  toDateTime64('$2', 9, 'UTC')"
}

# Distinct LOGICAL rows in [lo, hi), by the DESTINATION's ReplacingMergeTree dedup key. Reconciliation must be
# dedup-aware for the reason the traces driver gives — a raw count() differs between an un-merged source and a
# destination that deduped versions on insert — AND for one this table adds: the source's sort key carries
# parent_span_id and the destination's does not, so `spans` can hold two live rows for one span (SpanDAO's
# PARTIAL_INSERT writes a changed parent, and a mutable sort-key column never merges) where the successor holds one.
# Counting the SOURCE's own key would therefore report a faithful copy as short on every such span. Both sides count
# uniqExact of the DESTINATION key, which is what FINAL would collapse each to.
count_src_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, trace_id, id)
        FROM $SRC_TABLE
        WHERE created_at >= toDateTime64('$1', 9, 'UTC')
          AND created_at <  toDateTime64('$2', 9, 'UTC')"
}

count_dst_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, trace_id, id)
        FROM $DST_TABLE
        WHERE created_at >= toDateTime64('$1', 6, 'UTC')
          AND created_at <  toDateTime64('$2', 6, 'UTC')"
}

# Render and run the backfill statement for one window. $1/$2 are the created_at bounds.
run_backfill() {
    local lo="$1" hi="$2" sql token="spans_local_v2_backfill"
    sql="$(extract backfill)"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${WINDOW_LO}'/$lo}"
    sql="${sql//'${WINDOW_HI}'/$hi}"
    sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
    sql="${sql//'${MIN_INSERT_BLOCK_SIZE_BYTES}'/$MIN_INSERT_BLOCK_BYTES}"
    sql="${sql//'${MAX_PARTITIONS_PER_INSERT_BLOCK}'/$MAX_PARTITIONS}"
    # >>> BEGIN max_insert_threads rendering (fence for extracting this block to test edits -- keep the markers)
    # The SETTINGS line this depends on lives in ANOTHER file, so it is validated rather than assumed. The full
    # validation runs once at startup, per block (see the mit_require_one_assignment loop there), which is what makes
    # it exercised by --dry-run and reached before backfill_start is minted. Here it is re-resolved per window
    # because the render needs the line's position, and the file is re-read each window -- so an edit made
    # mid-run is still caught rather than rendered.
    #
    # Comments are masked before matching. A line-anchored match is NOT by itself a check that the assignment is
    # executable: an identical line inside a /* */ block carries a trailing comma too, and would otherwise be
    # treated as the assignment.
    # `|| true` is load-bearing: mit_assignment_lines ends in a pipeline whose grep exits 1 when there is no
    # match, so under `set -euo pipefail` this assignment would fail and `set -e` would kill the script HERE --
    # before the count check below could call mit_require_one_assignment. The zero case would exit 1 mutely,
    # which is the one case that most needs the diagnostic.
    mit_line="$(mit_assignment_lines "$sql" || true)"
    if [[ "$(grep -c . <<<"$mit_line" || true)" -ne 1 ]]; then
        mit_require_one_assignment "$sql" "$block" || exit 2
        exit 2
    fi
    if [[ -z "$MAX_INSERT_THREADS" ]]; then
        # Unset means INHERIT: drop the line so the server's own value applies. Rendering an explicit 0 would
        # OVERRIDE it and force the insert serial -- a slowdown, not a no-op.
        sql="$(sed "${mit_line}d" <<<"$sql")"
    else
        sql="$(sed "${mit_line}s/\\\${MAX_INSERT_THREADS}/${MAX_INSERT_THREADS}/" <<<"$sql")"
    fi
    # <<< END max_insert_threads rendering
    # The full validation contract, AFTER every substitution — which subsumes the traces driver's standalone
    # placeholder post-condition and adds the marker/identity checks the block extraction makes necessary.
    require_rendered "$sql" backfill "$token"
    clickhouse-client "${CH_ARGS[@]}" --time --multiquery --query "$sql"
}

# Insert one window whose physical row count is already within the per-window bound. Reconciliation is dedup-aware
# (uniqExact of the DESTINATION key) and concurrency-aware: a window still receiving writes (its created_at end is in
# the future) legitimately diverges during the copy — the delta-insert and deletion replay reconcile it — so an abort
# fires only on a genuine shortfall in a SETTLED window (a real backfill miss). "Settled" means no new rows by
# created_at; but a delete is NOT bounded by created_at and can mask a row in any window at any time, so the abort
# compares src and dst counted TOGETHER after the copy (a consistent snapshot) — never a stale pre-copy src against a
# fresh post-copy dst.
#
# THE TWO PASSES ARE RECONCILED TOGETHER, not separately, and that is deliberate: they are complements, so only their
# union is meaningful against the window's source count. A failure in either leaves the window short and the single
# post-copy comparison catches it — and because both statements are idempotent against a ReplacingMergeTree, re-running
# the window re-runs both harmlessly.
#
# Idempotent/resumable: a window already present on the destination is skipped.
insert_window() {
    local label="$1" lo="$2" hi="$3" src dst settled short block
    src="$(count_src_uniq "$lo" "$hi")"
    dst="$(count_dst_uniq "$lo" "$hi")"

    # Resume: skip only when the destination already holds at least as many logical rows as the source (exact, or ahead
    # because concurrent deletes shrank the source). DIVERGENCE is NOT a resume criterion: a partially-copied window can
    # sit a hair short of src yet within tolerance, and skipping it would leave those rows missing forever — the delta
    # step only re-copies rows at/after backfill_start, so a pre-anchor gap is unrepairable. The backfill INSERTs are
    # idempotent (ReplacingMergeTree, mask-honoring), so re-copying a short window is safe and cheap. DIVERGENCE governs
    # only the post-copy abort below.
    if [[ "$dst" != "0" && "$dst" -ge "$src" ]]; then
        log "$label ($lo .. $hi): already present (src_uniq=$src dst_uniq=$dst), skipping"
        return
    fi
    if [[ "$DRY_RUN" == "1" ]]; then
        log "$label ($lo .. $hi): would backfill ~$src rows"
        return
    fi

    log "$label ($lo .. $hi): backfilling ~$src rows"
    run_backfill "$lo" "$hi"

    # Recount BOTH sides after the copy: a row deleted on the source between the pre-copy src count and the post-copy dst
    # count is masked (so the mask-honoring INSERT never copied it) and would otherwise read as a shortfall and abort a
    # settled window falsely. Counting src and dst together after the copy compares like with like.
    src="$(count_src_uniq "$lo" "$hi")"
    dst="$(count_dst_uniq "$lo" "$hi")"
    settled="$(ch "SELECT now() >= toDateTime('$hi', 'UTC')")"
    short="$(awk -v s="$src" -v d="$dst" -v p="$DIVERGENCE" 'BEGIN { print (d < s && (s - d) / s > p) ? 1 : 0 }')"
    if [[ "$short" == "1" && "$settled" == "1" ]]; then
        log "ABORT $label ($lo .. $hi): destination short of a settled window (src_uniq=$src dst_uniq=$dst). Investigate before continuing." >&2
        log "       Investigate the statement's output above before continuing." >&2
        exit 1
    fi
    if [[ "$short" == "1" ]]; then
        log "$label ($lo .. $hi): live window (src_uniq=$src dst_uniq=$dst) — the delta-insert will reconcile concurrent writes"
    elif [[ "$dst" -gt "$src" ]]; then
        log "$label ($lo .. $hi): src_uniq=$src dst_uniq=$dst — concurrent source deletes; the deletion replay will reconcile"
    elif [[ "$dst" == "$src" ]]; then
        log "$label ($lo .. $hi): OK (src_uniq=dst_uniq=$src)"
    else
        log "$label ($lo .. $hi): OK within tolerance (src_uniq=$src dst_uniq=$dst)"
    fi

    if [[ "$PAUSE_SECONDS" != "0" ]]; then
        log "pausing ${PAUSE_SECONDS}s for merges to catch up"
        sleep "$PAUSE_SECONDS"
    fi
}

# Recursively bound a window to --max-rows-per-insert by halving it in time, then insert each leaf. Adaptive rather than
# fixed sub-windows so it holds under traffic skew (busy periods split more; quiet ones stay whole).
process_range() {
    local label="$1" lo="$2" hi="$3" src span mid
    src="$(count_src "$lo" "$hi")"
    if [[ "$src" == "0" ]]; then
        return
    fi
    span="$(ch "SELECT dateDiff('second', toDateTime('$lo', 'UTC'), toDateTime('$hi', 'UTC'))")"
    if [[ "$src" -le "$MAX_ROWS" || "$span" -le "$MIN_WINDOW_SECONDS" ]]; then
        insert_window "$label" "$lo" "$hi"
        return
    fi
    mid="$(ch "SELECT toString(addSeconds(toDateTime('$lo', 'UTC'), intDiv(toInt64($span), 2)))")"
    log "$label ($lo .. $hi): src=$src > $MAX_ROWS rows, splitting in half at $mid"
    process_range "$label" "$lo" "$mid"
    process_range "$label" "$mid" "$hi"
}

# Nothing to do on an empty table (min/max would return the epoch, not a real range).
ROWS="$(ch "SELECT count() FROM $SRC_TABLE")"
if [[ "$ROWS" == "0" ]]; then
    log "Source table is empty — nothing to backfill."
    exit 0
fi

# The successor must exist before anything else (runbook prerequisite #2: created empty by migration 000115, codecs
# refined by 000116). Checked explicitly so its absence reads as the prerequisite it is, rather than surfacing later as a
# raw ClickHouse "unknown table" from the capacity probe, the resume check or the first INSERT. Post-cutover the shadow
# has been renamed away, so this also stops a stray re-run after a completed cutover.
if [[ -z "$(ch "SELECT name FROM system.tables WHERE database = '$DATABASE' AND name = '$DST_TABLE'")" ]]; then
    log "ABORT: successor table '$DATABASE.$DST_TABLE' does not exist. It is created empty by Liquibase (migration 000115," >&2
    log "       codecs refined by 000116) — confirm those changesets are applied on this instance. If a cutover already" >&2
    log "       completed, the shadow was renamed away and there is nothing to backfill." >&2
    exit 1
fi

preflight_capacity

# backfill_start: the single anchor for BOTH the delta-insert and the deletion replay in step 2. Captured BEFORE the
# first INSERT so it covers every write during the (long) backfill, and persisted to --state-file so a resumed run
# reuses the ORIGINAL anchor. Re-minting a later anchor on resume would miss deletes that fired during the first run
# against already-copied rows. The operator MUST record it (also saved to the state file).
# The state file is READ and validated whenever it exists, dry run or not: keeping the guard behind DRY_RUN would let a
# full rehearsal pass while the real run aborts on the same file. Only minting and persisting need a real run.
if [[ -e "$STATE_FILE" ]]; then
    BACKFILL_START="$(cat "$STATE_FILE")"
    # An existing but EMPTY file is not "no anchor", and must not be read as one. The write below is a truncating
    # redirect, so a run killed between opening the file and writing to it leaves exactly this state. Treating it as
    # absent puts the two modes out of step on identical input -- a real run reaches the mint branch and its
    # destination-not-empty guard, while a dry run reaches neither and prints a plan for an anchor it never had.
    if [[ -z "$BACKFILL_START" ]]; then
        echo "ERROR: $STATE_FILE exists but is empty, so it holds no anchor to resume from. A run interrupted while" >&2
        echo "       persisting the anchor leaves this. If $DST_TABLE is still EMPTY nothing was copied against the" >&2
        echo "       lost anchor: delete $STATE_FILE and a fresh one is minted. If it is NOT empty, recover the" >&2
        echo "       original anchor and write it back, marker included:" >&2
        echo "         printf '%s UTC' '<original backfill_start>' > '$STATE_FILE'" >&2
        echo "       or restart the copy cleanly, which discards the partial shadow:" >&2
        echo "         ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --stage A" >&2
        exit 1
    fi
    # The anchor is stored with an explicit ' UTC' marker and is only accepted with it. The marker is not
    # decoration: step 2 parses this value AS UTC, so a value captured in some other zone silently shifts the
    # anchor, and a LATER anchor drops the writes and deletes in the gap from both the delta and the replay.
    # A file without the marker cannot be attributed to a timezone, so it is refused rather than reinterpreted.
    case "$BACKFILL_START" in
        *" UTC")
            BACKFILL_START="${BACKFILL_START% UTC}"
            ;;
        *)
            echo "ERROR: $STATE_FILE holds an anchor with no ' UTC' marker, so the timezone it was captured in" >&2
            echo "       cannot be established. Step 2 reads it as UTC; if it was captured server-local on a" >&2
            echo "       non-UTC server, the anchor moves by that offset and the delta and deletion replay both" >&2
            echo "       miss the rows written in the gap." >&2
            echo "       If you can confirm it was taken on a UTC server, re-record it explicitly:" >&2
            echo "         printf '%s UTC' '<anchor>' > '$STATE_FILE'" >&2
            echo "       If $DST_TABLE is still EMPTY this is a first run and no anchor is owed: delete" >&2
            echo "       $STATE_FILE and a fresh one is minted. Otherwise restart the copy cleanly, which" >&2
            echo "       discards the partial shadow:" >&2
            echo "         ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --stage A" >&2
            exit 1
            ;;
    esac
    # Shape check, after the marker: the file is operator-owned, so a corrupted value would otherwise feed a
    # garbage anchor forward to step 2.
    [[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: $STATE_FILE does not contain a valid backfill_start timestamp ('YYYY-MM-DD HH:MM:SS[.ffffff] UTC')." >&2; exit 1; }
    # Logged WITH the marker: this is the only place a resumed run shows the anchor, and it is the value the operator
    # pastes into step 2 and step 3, both of which refuse it without one.
    log "REUSING backfill_start=$BACKFILL_START UTC from $STATE_FILE (resume: original anchor kept)"
elif [[ "$DRY_RUN" != "1" ]]; then
    # Refuse to mint a FRESH anchor onto a destination that already holds rows. That combination is contradictory:
    # a genuine first run starts from an empty successor (migration 000115 creates it empty; a stage-A rollback
    # truncates it back to empty), so a non-empty destination means this is a RESUME whose original anchor
    # has been lost — most often because --state-file defaults to a CWD-relative path and the resume ran from a
    # different directory. Minting a later anchor there is silent data loss: deletes that fired between the real
    # anchor and this one, against rows the earlier run already copied, are seen by neither the delta (bounded by
    # the anchor) nor the deletion replay (same bound), so they leak live across the EXCHANGE. The pre-EXCHANGE
    # verify.sh would flag it, but only as a late, hard-to-attribute mismatch after the copy has been redone — and
    # on a table that takes days to copy, "redone" is the expensive word in that sentence.
    DST_ROWS_AT_ANCHOR="$(ch "SELECT count() FROM $DST_TABLE")"
    if [[ "$DST_ROWS_AT_ANCHOR" != "0" ]]; then
        log "ABORT: no anchor in '$STATE_FILE', but $DST_TABLE already holds $DST_ROWS_AT_ANCHOR row(s) — this is a RESUME whose" >&2
        log "       original backfill_start was lost, and minting a fresh (later) anchor would make the delta and the" >&2
        log "       deletion replay blind to deletes in the gap, leaking them across the EXCHANGE. Recover the original" >&2
        log "       anchor, then either point --state-file at the file holding it or write it back:" >&2
        log "         printf '%s UTC' '<original backfill_start>' > '$STATE_FILE'" >&2
        log "       If it is unrecoverable, restart the copy cleanly instead (discards the partial shadow):" >&2
        log "         ./rollback.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --stage A" >&2
        exit 1
    fi
    # Captured in UTC because step 2 parses it as UTC (see 000002). Both halves must agree: read back in another
    # timezone the anchor moves by the server's offset, and a later anchor drops the writes in the gap.
    BACKFILL_START="$(ch "SELECT toString(now64(6, 'UTC'))")"
    printf '%s UTC' "$BACKFILL_START" > "$STATE_FILE"
    log "RECORD backfill_start=$BACKFILL_START UTC  (saved to $STATE_FILE; pass this, marker included, to step 2: 000002_delta_and_deletion_replay.sql)"
fi

# The anchor is the Monday of the earliest row; the horizon is the Monday after the latest row. All week boundaries are
# computed from the anchor in ClickHouse (addWeeks), so there is no host-side date math or timezone ambiguity.
ANCHOR="$(ch "SELECT toString(toMonday(min(created_at))) FROM $SRC_TABLE")"
HORIZON="$(ch "SELECT toString(addWeeks(toMonday(max(created_at)), 1)) FROM $SRC_TABLE")"
LAST_WEEK="$(ch "SELECT dateDiff('week', toDate('$ANCHOR'), toDate('$HORIZON')) - 1")"
[[ -n "$TO_WEEK" ]] || TO_WEEK="$LAST_WEEK"

log "Anchor Monday: $ANCHOR | horizon: $HORIZON | weeks: [$FROM_WEEK..$TO_WEEK] | max-rows/window: $MAX_ROWS | pause: ${PAUSE_SECONDS}s | dry-run: $DRY_RUN"
log "Block bounds: max_partitions_per_insert_block=$MAX_PARTITIONS, min_insert_block_size_bytes=$MIN_INSERT_BLOCK_BYTES, max_insert_block_size=$MAX_INSERT_BLOCK_SIZE"

for (( week=FROM_WEEK; week<=TO_WEEK; week++ )); do
    LO="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $week))") 00:00:00"
    HI="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $((week + 1))))") 00:00:00"
    process_range "week $week" "$LO" "$HI"
done

log "Backfill complete for weeks [$FROM_WEEK..$TO_WEEK]. Proceed to step 2 (delta + deletion replay)."
