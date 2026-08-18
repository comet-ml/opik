#!/usr/bin/env bash
#
# Backfill driver for the buffered traces cutover (runbook: ../README.md, step 1).
#
# Copies traces -> traces_local_v2 oldest to newest, reconciling and aborting on divergence. It iterates by week (for
# progress and --from-week resume), but each week is further split, adaptively, into time sub-windows so that no single
# INSERT moves more than --max-rows-per-insert rows. On a large production table a whole week can be enormous; bounding
# each statement keeps its duration, its blast radius on failure, and the destination part-count it creates all in
# check. Memory is separately bounded by ClickHouse's block squashing (see --max-insert-block-size below).
#
# Week boundaries are derived from the data (toMonday(min/max(created_at))) — the operator does not hand-write dates.
# Idempotent and resumable: a window whose destination count already matches the source is skipped.
#
# The backfill INSERT is NOT duplicated here: it is read from db-app-analytics/000001_backfill_traces_local_v2.sql
# (the single source), with the ${...} placeholders substituted per window. See README "How backfill.sh and 000001
# relate", "Why slice by created_at", and "Batching and throttling".
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
#   --dry-run                 print the window plan and per-window source counts; do not INSERT.
#   --from-week N             start at week offset N (0-based from the anchor Monday). Default 0.
#   --to-week M               stop after week offset M (inclusive). Default: last week with data.
#   --max-rows-per-insert R   upper bound on rows per INSERT statement; a week over this is halved by time until each
#                             sub-window fits. Default 2000000. Smaller = safer per statement but more parts / merge
#                             pressure; larger = fewer parts but bigger blast radius. This is a per-statement bound, not
#                             a memory bound (see --max-insert-block-size).
#   --max-insert-block-size N rows per block ClickHouse forms while writing (SETTINGS max_insert_block_size). Peak insert
#                             memory is a small multiple of the smaller of this and min_insert_block_size_bytes (256 MB
#                             default), so for wide trace rows the byte bound usually dominates. Default 1048576 (the
#                             ClickHouse default); lower it on a memory-constrained data node. Applied to the INSERT.
#   --max-partitions-per-insert-block N
#                             partitions one insert block may span (SETTINGS max_partitions_per_insert_block).
#                             Default 2000; 0 = unlimited. The destination is weekly-partitioned on the honest Monday
#                             of id_at, so a block spans as many partitions as the ids in it imply — NOT one. ClickHouse
#                             defaults this to 100 and, with throw_on_max_partitions_per_insert_block = 1, ABORTS the
#                             INSERT rather than degrading. Far-future UUIDv7 ids (litellm BerriAI/litellm#31294) make
#                             that reachable on real data: measured on a production-shape table, one block spanned 333
#                             destination partitions in total, 269 of them far-future (the rest ordinary weeks the same
#                             block touched), against a window holding 275 far-future partitions. Note the implication
#                             for sizing: a block's spread is NOT just the far-future count, so size from the table's
#                             TOTAL distinct partition count. Raising it trades a larger part count per insert (one part
#                             per partition touched, compacted by background merges) for the INSERT completing at all.
#                             See the runbook's "Far-future partitions from far-future-timestamp ids".
#   --max-insert-threads N    threads for the INSERT SELECT pipeline (SETTINGS max_insert_threads).
#                             Default 0. ClickHouse documents 0 (or 1) as "INSERT SELECT no parallel
#                             execution", so the copy runs its insert side single-threaded unless this
#                             is raised. NOTE the setting is scoped to INSERT SELECT, which is what
#                             this backfill issues; it is not a general INSERT knob. NOTE ALSO that
#                             ClickHouse CLOUD does not default it to 0 -- upstream documents 1 / 2 / 4
#                             by node memory -- so on Cloud you may already have parallelism here.
#
#                             Raising it lets you decide how much of the machine the backfill may use,
#                             and can speed the copy up substantially when the insert side is the
#                             constraint.
#
#                             PRECONDITION, per upstream: "Parallel INSERT SELECT has effect only if
#                             the SELECT part is executed in parallel" (see max_threads). If the read
#                             side is serialised, raising this buys nothing.
#
#                             WHY THE INSERT SIDE IS OFTEN THE CONSTRAINT HERE: the destination carries
#                             per-row MATERIALIZED work the source does not do. The expensive one is
#                             output_keys, which PARSES the output JSON:
#                               arrayMap(key -> tuple(key, toString(JSONType(JSONExtractRaw(output, key)))),
#                                        JSONExtractKeys(output))
#                             There is NO input_keys column -- output_keys is traces-only and has no input
#                             counterpart; do not go looking for one. The table also materialises
#                             truncated_input / truncated_output, which substring-copy documents that can
#                             be very large, plus input_length / output_length / metadata_length, duration
#                             and id_at.
#
#                             Upstream states materialized values "are automatically calculated ... when
#                             rows are inserted", but does NOT state which pipeline stage or which threads
#                             compute them; that the insert side is the bottleneck on this table is an
#                             INFERENCE FROM PROFILING, not a documented guarantee -- see below for how
#                             to confirm it on your own data rather than assuming it.
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
#                                 memory usage." That matters more on this table than it might
#                                 elsewhere, because a single oversized `output` document can dominate
#                                 insert memory on its own. Raise this and max_memory_usage together,
#                                 or narrow the window, rather than raising threads alone into a fixed
#                                 ceiling.
#                             (2) PARTS. Each insert thread writes its own parts, so part count per
#                                 partition grows. Watch it against parts_to_throw_insert (ClickHouse
#                                 default 300) rather than assuming headroom.
#
#                             CHOOSING A VALUE IS A CAPACITY DECISION, NOT A BENCHMARK. On an idle
#                             rehearsal environment a large value looks free; on a production cluster
#                             those threads compete with live query latency. Pick the share of cores
#                             the cutover may take while serving traffic, and validate the value you
#                             will actually deploy rather than the largest one that fits. 0 stays the
#                             default so no existing deployment changes behaviour, and so a small
#                             install on few cores does not silently start spawning insert threads.
#   --divergence P            max tolerated |src-dst|/src per window before aborting. Default 0.0001 (0.01%).
#   --pause-seconds S         sleep S seconds after each inserted window, to let destination merges catch up and bound
#                             the part count / IO pressure. Default 0. Recommended 30-60 on a large table at peak.
#   --min-free-factor F       abort at startup unless node free disk >= F x the current `traces` on-disk size (the
#                             backfill writes a full second copy). Default 2.0. Pass 0 to skip the check. This is a
#                             whole-node floor; on tiered storage validate per-volume (hot) headroom separately.
#   --confirm-tiered-headroom  REQUIRED when the destination storage_policy is tiered (multi-volume) or differs from the
#                             source's. The whole-node --min-free-factor check cannot see per-volume headroom, and new
#                             parts land on the hot volume before they tier; this asserts the operator validated hot
#                             headroom out of band. (No effect on a single-volume/default policy.)
#   --state-file PATH         file the captured backfill_start is written to and reused from. On resume the ORIGINAL
#                             anchor is kept; re-minting a later one would miss deletes that fired during the first run
#                             against already-copied rows. Default ./traces_cutover_backfill_start — note this is
#                             CWD-RELATIVE, so resuming from a different directory would not find it; pass an absolute
#                             path for a multi-session cutover. If the anchor is missing while the destination already
#                             holds rows, the script ABORTS rather than mint a later one (that would leak deletes).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKFILL_SQL="$SCRIPT_DIR/db-app-analytics/000001_backfill_traces_local_v2.sql"

# Fixed source/destination of this migration. The backfill INSERT itself lives in 000001; these are only for the
# script's own sizing and reconciliation queries.
SRC_TABLE="traces"
DST_TABLE="traces_local_v2"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
DRY_RUN=0
FROM_WEEK=0
TO_WEEK=""
MAX_ROWS=2000000          # rows: per-statement bound; a week over this is halved in time until each insert fits. Caps
                          # each INSERT's duration, blast radius and destination part count. NOT a memory bound.
MAX_INSERT_BLOCK_SIZE=1048576  # rows: SETTINGS max_insert_block_size for the INSERT. Peak memory is a small multiple of
                          # the smaller of this and min_insert_block_size_bytes (256 MB default), which dominates for wide
                          # trace rows; lower it on a memory-constrained node. 1048576 is the ClickHouse default.
                          # 0 = ClickHouse default = SINGLE-THREADED sink, usually the throughput
                          # ceiling here. See --max-insert-threads above for the measurements.
MAX_PARTITIONS_PER_INSERT_BLOCK=2000  # partitions: SETTINGS max_partitions_per_insert_block for the INSERT. The
MAX_INSERT_THREADS=0                  # threads for the INSERT SELECT pipeline (SETTINGS max_insert_threads).
                          # destination is weekly-partitioned, so one block can span many partitions; ClickHouse's
                          # default of 100 THROWS (throw_on_max_partitions_per_insert_block=1). Far-future UUIDv7 ids
                          # make this reachable in practice — see the runbook's far-future section. 0 = unlimited.
DIVERGENCE="0.0001"       # fraction: max tolerated |src-dst|/src per settled window before aborting (0.01%).
PAUSE_SECONDS=0           # seconds: sleep after each inserted window so destination merges catch up. 30-60 for a large table at peak.
MIN_FREE_FACTOR="2.0"     # multiple of the current traces on-disk size that node free space must clear before starting.
STATE_FILE="./traces_cutover_backfill_start"  # backfill_start is persisted here and reused on resume (keeps one anchor).
CONFIRM_TIERED_HEADROOM=0 # required when the destination storage_policy is tiered/mismatched (see preflight_capacity).

# Floor on adaptive splitting: never divide a window shorter than this. Guards against splitting forever on a single
# hot instant; such a window is inserted whole (memory is still bounded by block squashing).
MIN_WINDOW_SECONDS=60

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --from-week) FROM_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --to-week) TO_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-rows-per-insert) MAX_ROWS="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --divergence) DIVERGENCE="${2:?"$1 requires a value"}"; shift 2 ;;
        --pause-seconds) PAUSE_SECONDS="${2:?"$1 requires a value"}"; shift 2 ;;
        --min-free-factor) MIN_FREE_FACTOR="${2:?"$1 requires a value"}"; shift 2 ;;
        --confirm-tiered-headroom) CONFIRM_TIERED_HEADROOM=1; shift ;;
        --state-file) STATE_FILE="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
# --state-file is an operator-owned path read with cat and written with > (both quoted); reject a blank or multi-line
# value so the single-line anchor round-trips cleanly.
[[ -n "$STATE_FILE" && "$STATE_FILE" != *$'\n'* ]] || { echo "ERROR: --state-file must be a non-empty single-line path." >&2; exit 2; }
# Numeric args flow into the reference SQL / window arithmetic; require sane numeric shapes so none can alter the query.
[[ "$MAX_ROWS" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-rows-per-insert must be a positive integer." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
# 0 is meaningful here (ClickHouse reads it as "unlimited"), so allow it — unlike the bounds above. Upper-bounded at 6
# digits: the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be
# rendered into the SQL and rejected by the server on the first INSERT — after the capacity preflight has passed and the
# backfill_start anchor has been minted, which is a far more expensive place to discover a typo.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
# 0 is meaningful (ClickHouse default, single-threaded sink), so allow it. Bounded at 2 digits: this is
# a share of cores, and a value beyond the machine's core count buys nothing while multiplying parts.
[[ "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (ClickHouse default: no parallel INSERT SELECT execution) or 1..99." >&2; exit 2; }
[[ "$FROM_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --from-week must be a non-negative integer." >&2; exit 2; }
[[ -z "$TO_WEEK" || "$TO_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --to-week must be a non-negative integer." >&2; exit 2; }
[[ "$PAUSE_SECONDS" =~ ^[0-9]+$ ]] || { echo "ERROR: --pause-seconds must be a non-negative integer." >&2; exit 2; }
[[ "$DIVERGENCE" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --divergence must be a number." >&2; exit 2; }
[[ "$MIN_FREE_FACTOR" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ERROR: --min-free-factor must be a number." >&2; exit 2; }
[[ -f "$BACKFILL_SQL" ]] || { echo "ERROR: cannot find backfill SQL at $BACKFILL_SQL" >&2; exit 2; }

# Every query runs against the analytics database; --query keeps output scriptable (TSV, no formatting).
ch() {
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --log_comment 'traces_local_v2_cutover:backfill' --query "$1"
}

log() {
    echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
}

bytes_gib() {
    awk -v b="$1" 'BEGIN { printf "%.1f", b / 1073741824 }'
}

# Capacity pre-flight. The backfill writes a full second physical copy of `traces` (peak ~2x on-disk, more counting
# merge scratch), so abort unless node free space clears --min-free-factor x the current size. This whole-node total is
# a necessary floor, not sufficient on tiered storage: new parts land on the hot volume before they tier, so validate
# per-volume headroom separately. Also warn (not abort) if the successor's storage_policy differs from the source's — a
# mismatch means the copy would not tier the same way and could fill the hot volume even when the node total looks fine.
preflight_capacity() {
    local traces_bytes free_bytes need src_policy dst_policy
    traces_bytes="$(ch "SELECT sum(bytes_on_disk) FROM system.parts WHERE database = '$DATABASE' AND table = '$SRC_TABLE' AND active")"
    free_bytes="$(ch "SELECT sum(free_space) FROM system.disks")"
    log "Capacity: $SRC_TABLE on-disk $(bytes_gib "$traces_bytes") GiB, node free $(bytes_gib "$free_bytes") GiB, need >= ${MIN_FREE_FACTOR}x"
    if [[ "$MIN_FREE_FACTOR" != "0" ]]; then
        need="$(awk -v t="$traces_bytes" -v k="$MIN_FREE_FACTOR" 'BEGIN { printf "%d", t * k }')"
        if [[ "$(awk -v f="$free_bytes" -v n="$need" 'BEGIN { print (f < n) ? 1 : 0 }')" == "1" ]]; then
            log "ABORT: node free disk $(bytes_gib "$free_bytes") GiB is below ${MIN_FREE_FACTOR}x $SRC_TABLE ($(bytes_gib "$need") GiB). Free space, or pass --min-free-factor 0 to override once per-volume headroom is validated." >&2
            exit 1
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

# Distinct LOGICAL rows in [lo, hi), by the ReplacingMergeTree dedup key. Reconciliation must be dedup-aware: raw
# count() differs between an un-merged source and a destination that deduped duplicate versions on insert
# (optimize_on_insert), even for a perfect copy. uniqExact of the key is what FINAL would collapse to on each side.
count_src_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, id)
        FROM $SRC_TABLE
        WHERE created_at >= toDateTime64('$1', 9, 'UTC')
          AND created_at <  toDateTime64('$2', 9, 'UTC')"
}

count_dst_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, id)
        FROM $DST_TABLE
        WHERE created_at >= toDateTime64('$1', 6, 'UTC')
          AND created_at <  toDateTime64('$2', 6, 'UTC')"
}

# Render the reference INSERT for one window by substituting placeholders (pure bash, no envsubst dependency).
run_backfill_window() {
    local lo="$1" hi="$2" sql
    sql="$(cat "$BACKFILL_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${WINDOW_LO}'/$lo}"
    sql="${sql//'${WINDOW_HI}'/$hi}"
    sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
    sql="${sql//'${MAX_PARTITIONS_PER_INSERT_BLOCK}'/$MAX_PARTITIONS_PER_INSERT_BLOCK}"
    sql="${sql//'${MAX_INSERT_THREADS}'/$MAX_INSERT_THREADS}"
    clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --multiquery --query "$sql"
}

# Insert one window whose physical row count is already within the per-statement bound. Reconciliation is dedup-aware
# (uniqExact) and concurrency-aware: a window still receiving writes (its created_at end is in the future) legitimately
# diverges during the copy — the delta-insert and deletion replay reconcile it — so an abort fires only on a genuine
# shortfall in a SETTLED window (a real backfill miss). "Settled" means no new rows by created_at; but a delete is NOT
# bounded by created_at and can mask a row in any window at any time, so the abort compares src and dst counted TOGETHER
# after the copy (a consistent snapshot) — never a stale pre-copy src against a fresh post-copy dst.
# Idempotent/resumable: a window already present on the destination is skipped.
insert_window() {
    local label="$1" lo="$2" hi="$3" src dst settled short
    src="$(count_src_uniq "$lo" "$hi")"
    dst="$(count_dst_uniq "$lo" "$hi")"

    # Resume: skip only when the destination already holds at least as many logical rows as the source (exact, or ahead
    # because concurrent deletes shrank the source). DIVERGENCE is NOT a resume criterion: a partially-copied window can
    # sit a hair short of src yet within tolerance, and skipping it would leave those rows missing forever — the delta
    # step only re-copies rows at/after backfill_start, so a pre-anchor gap is unrepairable. The backfill INSERT is
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
    run_backfill_window "$lo" "$hi"

    # Recount BOTH sides after the copy: a row deleted on the source between the pre-copy src count and the post-copy dst
    # count is masked (so the mask-honoring INSERT never copied it) and would otherwise read as a shortfall and abort a
    # settled window falsely. Counting src and dst together after the copy compares like with like.
    src="$(count_src_uniq "$lo" "$hi")"
    dst="$(count_dst_uniq "$lo" "$hi")"
    settled="$(ch "SELECT now() >= toDateTime('$hi', 'UTC')")"
    short="$(awk -v s="$src" -v d="$dst" -v p="$DIVERGENCE" 'BEGIN { print (d < s && (s - d) / s > p) ? 1 : 0 }')"
    if [[ "$short" == "1" && "$settled" == "1" ]]; then
        log "ABORT $label ($lo .. $hi): destination short of a settled window (src_uniq=$src dst_uniq=$dst). Investigate before continuing." >&2
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

# The successor must exist before anything else (runbook prerequisite #2: created empty by migration 000101, recreated by
# 000114). Checked explicitly so its absence reads as the prerequisite it is, rather than surfacing later as a raw
# ClickHouse "unknown table" from the capacity probe, the resume check or the first INSERT. Post-cutover the shadow has
# been renamed away, so this also stops a stray re-run after a completed cutover.
if [[ -z "$(ch "SELECT name FROM system.tables WHERE database = '$DATABASE' AND name = '$DST_TABLE'")" ]]; then
    log "ABORT: successor table '$DATABASE.$DST_TABLE' does not exist. It is created empty by Liquibase (migration 000101," >&2
    log "       recreated by 000114) — confirm those changesets are applied on this instance. If a cutover already" >&2
    log "       completed, the shadow was renamed away and there is nothing to backfill." >&2
    exit 1
fi

preflight_capacity

# backfill_start: the single anchor for BOTH the delta-insert and the deletion replay in step 2. Captured BEFORE the
# first INSERT so it covers every write during the (long) backfill, and persisted to --state-file so a resumed run
# reuses the ORIGINAL anchor. Re-minting a later anchor on resume would miss deletes that fired during the first run
# against already-copied rows. The operator MUST record it (also saved to the state file).
if [[ "$DRY_RUN" != "1" ]]; then
    if [[ -s "$STATE_FILE" ]]; then
        BACKFILL_START="$(cat "$STATE_FILE")"
        # Validate the resumed content: the state file is operator-owned, so a corrupted or wrong file would otherwise
        # feed a garbage anchor forward to step 2. Fail fast unless it is a well-formed timestamp.
        [[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: $STATE_FILE does not contain a valid backfill_start timestamp ('YYYY-MM-DD HH:MM:SS[.ffffff]')." >&2; exit 1; }
        log "REUSING backfill_start=$BACKFILL_START from $STATE_FILE (resume: original anchor kept)"
    else
        # Refuse to mint a FRESH anchor onto a destination that already holds rows. That combination is contradictory:
        # a genuine first run starts from an empty successor (migration 000101/000114 creates it empty; a stage-A
        # rollback truncates it back to empty), so a non-empty destination means this is a RESUME whose original anchor
        # has been lost — most often because --state-file defaults to a CWD-relative path and the resume ran from a
        # different directory. Minting a later anchor there is silent data loss: deletes that fired between the real
        # anchor and this one, against rows the earlier run already copied, are seen by neither the delta (bounded by
        # the anchor) nor the deletion replay (same bound), so they leak live across the EXCHANGE. The pre-EXCHANGE
        # verify.sh would flag it, but only as a late, hard-to-attribute mismatch after the copy has been redone.
        DST_ROWS_AT_ANCHOR="$(ch "SELECT count() FROM $DST_TABLE")"
        if [[ "$DST_ROWS_AT_ANCHOR" != "0" ]]; then
            log "ABORT: no anchor in '$STATE_FILE', but $DST_TABLE already holds $DST_ROWS_AT_ANCHOR row(s) — this is a RESUME whose" >&2
            log "       original backfill_start was lost, and minting a fresh (later) anchor would make the delta and the" >&2
            log "       deletion replay blind to deletes in the gap, leaking them across the EXCHANGE. Recover the original" >&2
            log "       anchor, then either point --state-file at the file holding it or write it back:" >&2
            log "         printf '%s' '<original backfill_start>' > '$STATE_FILE'" >&2
            log "       If it is unrecoverable, restart the copy cleanly instead (discards the partial shadow):" >&2
            log "         ./rollback.sh --database $DATABASE --stage A" >&2
            exit 1
        fi
        BACKFILL_START="$(ch "SELECT toString(now64(6))")"
        printf '%s' "$BACKFILL_START" > "$STATE_FILE"
        log "RECORD backfill_start=$BACKFILL_START  (saved to $STATE_FILE; pass this to step 2: 000002_delta_and_deletion_replay.sql)"
    fi
fi

# The anchor is the Monday of the earliest row; the horizon is the Monday after the latest row. All week boundaries are
# computed from the anchor in ClickHouse (addWeeks), so there is no host-side date math or timezone ambiguity.
ANCHOR="$(ch "SELECT toString(toMonday(min(created_at))) FROM $SRC_TABLE")"
HORIZON="$(ch "SELECT toString(addWeeks(toMonday(max(created_at)), 1)) FROM $SRC_TABLE")"
LAST_WEEK="$(ch "SELECT dateDiff('week', toDate('$ANCHOR'), toDate('$HORIZON')) - 1")"
[[ -n "$TO_WEEK" ]] || TO_WEEK="$LAST_WEEK"

log "Anchor Monday: $ANCHOR | horizon: $HORIZON | weeks: [$FROM_WEEK..$TO_WEEK] | max-rows/insert: $MAX_ROWS | pause: ${PAUSE_SECONDS}s | dry-run: $DRY_RUN"

for (( week=FROM_WEEK; week<=TO_WEEK; week++ )); do
    LO="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $week))") 00:00:00"
    HI="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $((week + 1))))") 00:00:00"
    process_range "week $week" "$LO" "$HI"
done

log "Backfill complete for weeks [$FROM_WEEK..$TO_WEEK]. Proceed to step 2 (delta + deletion replay)."
