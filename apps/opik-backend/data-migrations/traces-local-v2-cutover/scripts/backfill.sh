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
# Connection: passed straight to clickhouse-client via the standard env vars it honors
# (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD). --database is required.
#
# Options:
#   --database NAME           analytics database (e.g. opik). Required.
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
#   --divergence P            max tolerated |src-dst|/src per window before aborting. Default 0.0001 (0.01%).
#   --pause-seconds S         sleep S seconds after each inserted window, to let destination merges catch up and bound
#                             the part count / IO pressure. Default 0. Recommended 30-60 on the ~4 TB table at peak.
#   --min-free-factor F       abort at startup unless node free disk >= F x the current `traces` on-disk size (the
#                             backfill writes a full second copy). Default 2.0. Pass 0 to skip the check. This is a
#                             whole-node floor; on tiered storage validate per-volume (hot) headroom separately.
#   --state-file PATH         file the captured backfill_start is written to and reused from. On resume the ORIGINAL
#                             anchor is kept; re-minting a later one would miss deletes that fired during the first run
#                             against already-copied rows. Default ./traces_cutover_backfill_start.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKFILL_SQL="$SCRIPT_DIR/db-app-analytics/000001_backfill_traces_local_v2.sql"

# Fixed source/destination of this migration. The backfill INSERT itself lives in 000001; these are only for the
# script's own sizing and reconciliation queries.
SRC_TABLE="traces"
DST_TABLE="traces_local_v2"

DATABASE=""
DRY_RUN=0
FROM_WEEK=0
TO_WEEK=""
MAX_ROWS=2000000          # rows: per-statement bound; a week over this is halved in time until each insert fits. Caps
                          # each INSERT's duration, blast radius and destination part count. NOT a memory bound.
MAX_INSERT_BLOCK_SIZE=1048576  # rows: SETTINGS max_insert_block_size for the INSERT. Peak memory is a small multiple of
                          # the smaller of this and min_insert_block_size_bytes (256 MB default), which dominates for wide
                          # trace rows; lower it on a memory-constrained node. 1048576 is the ClickHouse default.
DIVERGENCE="0.0001"       # fraction: max tolerated |src-dst|/src per settled window before aborting (0.01%).
PAUSE_SECONDS=0           # seconds: sleep after each inserted window so destination merges catch up. 30-60 at ~4 TB peak.
MIN_FREE_FACTOR="2.0"     # multiple of the current traces on-disk size that node free space must clear before starting.
STATE_FILE="./traces_cutover_backfill_start"  # backfill_start is persisted here and reused on resume (keeps one anchor).

# Floor on adaptive splitting: never divide a window shorter than this. Guards against splitting forever on a single
# hot instant; such a window is inserted whole (memory is still bounded by block squashing).
MIN_WINDOW_SECONDS=60

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --from-week) FROM_WEEK="$2"; shift 2 ;;
        --to-week) TO_WEEK="$2"; shift 2 ;;
        --max-rows-per-insert) MAX_ROWS="$2"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="$2"; shift 2 ;;
        --divergence) DIVERGENCE="$2"; shift 2 ;;
        --pause-seconds) PAUSE_SECONDS="$2"; shift 2 ;;
        --min-free-factor) MIN_FREE_FACTOR="$2"; shift 2 ;;
        --state-file) STATE_FILE="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database is interpolated into the reference SQL; require a plain ClickHouse identifier so it cannot alter the query.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
# --state-file is an operator-owned path read with cat and written with > (both quoted); reject a blank or multi-line
# value so the single-line anchor round-trips cleanly.
[[ -n "$STATE_FILE" && "$STATE_FILE" != *$'\n'* ]] || { echo "ERROR: --state-file must be a non-empty single-line path." >&2; exit 2; }
[[ -f "$BACKFILL_SQL" ]] || { echo "ERROR: cannot find backfill SQL at $BACKFILL_SQL" >&2; exit 2; }

# Every query runs against the analytics database; --query keeps output scriptable (TSV, no formatting).
ch() {
    clickhouse-client --database "$DATABASE" --query "$1"
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
}

# Live source rows in [lo, hi). count() honors the deleted-row mask, so masked rows are excluded (they must not copy).
# This is a PHYSICAL row count, used only to size sub-windows against --max-rows-per-insert (not for reconciliation).
count_src() {
    ch "SELECT count()
        FROM $SRC_TABLE
        WHERE created_at >= toDateTime64('$1', 9)
          AND created_at <  toDateTime64('$2', 9)"
}

# Distinct LOGICAL rows in [lo, hi), by the ReplacingMergeTree dedup key. Reconciliation must be dedup-aware: raw
# count() differs between an un-merged source and a destination that deduped duplicate versions on insert
# (optimize_on_insert), even for a perfect copy. uniqExact of the key is what FINAL would collapse to on each side.
count_src_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, id)
        FROM $SRC_TABLE
        WHERE created_at >= toDateTime64('$1', 9)
          AND created_at <  toDateTime64('$2', 9)"
}

count_dst_uniq() {
    ch "SELECT uniqExact(workspace_id, project_id, id)
        FROM $DST_TABLE
        WHERE created_at >= toDateTime64('$1', 6)
          AND created_at <  toDateTime64('$2', 6)"
}

# Render the reference INSERT for one window by substituting placeholders (pure bash, no envsubst dependency).
run_backfill_window() {
    local lo="$1" hi="$2" sql
    sql="$(cat "$BACKFILL_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${WINDOW_LO}'/$lo}"
    sql="${sql//'${WINDOW_HI}'/$hi}"
    sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
    clickhouse-client --database "$DATABASE" --multiquery --query "$sql"
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

    # Resume: destination already has this window (exact, ahead due to concurrent deletes, or within tolerance).
    if [[ "$dst" != "0" ]] \
        && [[ "$(awk -v s="$src" -v d="$dst" -v p="$DIVERGENCE" 'BEGIN { print (d >= s || (s - d) / s <= p) ? 1 : 0 }')" == "1" ]]; then
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
    else
        log "$label ($lo .. $hi): OK (src_uniq=dst_uniq=$src)"
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

preflight_capacity

# backfill_start: the single anchor for BOTH the delta-insert and the deletion replay in step 2. Captured BEFORE the
# first INSERT so it covers every write during the (long) backfill, and persisted to --state-file so a resumed run
# reuses the ORIGINAL anchor. Re-minting a later anchor on resume would miss deletes that fired during the first run
# against already-copied rows. The operator MUST record it (also saved to the state file).
if [[ "$DRY_RUN" != "1" ]]; then
    if [[ -s "$STATE_FILE" ]]; then
        BACKFILL_START="$(cat "$STATE_FILE")"
        log "REUSING backfill_start=$BACKFILL_START from $STATE_FILE (resume: original anchor kept)"
    else
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
