#!/usr/bin/env bash
#
# Fidelity QA driver for the buffered traces cutover (runbook: ../README.md, "Verifying the migration").
#
# Compares the migrated data on the old-schema and new-schema tables, week by week (created_at), using a NORMALIZED
# fingerprint so sentinel/precision differences (end_time NULL<->epoch, ttft NULL<->NaN, ns<->us) do not count as
# changes. For each week it reads one (row count, checksum) verdict per side; a mismatch means that week's live, deduped
# content differs. With --drill-down, a mismatched week is followed by a per-key listing of the rows that differ. Exits
# non-zero if any window mismatched.
#
# The compare and drill-down SQL are NOT duplicated here: both are read from db-app-analytics/000005_verify_migration.sql
# (the single source, and the exact normalization the gate test asserts). See README "Verifying the migration".
#
# Feasibility on a large table: full mode reads every partition (heavy but bounded per week; run off-peak). --sample-mod
# compares a deterministic id sample (same rows on both sides); --weeks-stride compares every Nth week; --from/--to-week
# limit the range. Comparing a representative subset gives high confidence when a full pass is infeasible.
#
# Usage:
#   CLICKHOUSE_HOST=... CLICKHOUSE_PASSWORD=... ./verify.sh --database opik [options]
#
# Options:
#   --database NAME     analytics database (e.g. opik). Required.
#   --old-table NAME    old-schema table (Nullable, nanosecond). Default traces. After the EXCHANGE: traces_pre_cutover_backup.
#   --new-table NAME    new-schema table (sentinels, microsecond). Default traces_local_v2. After the EXCHANGE: traces.
#   --sample-mod N      compare a deterministic 1/N id sample (same ids on both sides). Default 1 (every row).
#   --from-week N       start at week offset N (0-based from the anchor Monday). Default 0.
#   --to-week M         stop after week offset M (inclusive). Default: last week with data.
#   --weeks-stride S    compare every S-th week (S>1 samples partitions for a quick pass). Default 1.
#   --drill-down        on a mismatched week, also print up to 100 keys that differ or exist on one side only.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SQL="$SCRIPT_DIR/db-app-analytics/000005_verify_migration.sql"

DATABASE=""
OLD_TABLE="traces"          # old-schema side; becomes traces_pre_cutover_backup after the EXCHANGE (see --old-table)
NEW_TABLE="traces_local_v2" # new-schema side (the successor being built); becomes traces after the EXCHANGE
SAMPLE_MOD=1                # 1 = every row; N compares a deterministic 1/N id sample, identical on both sides
FROM_WEEK=0
TO_WEEK=""
WEEKS_STRIDE=1              # 1 = every week; S skips to every S-th weekly partition for a quick, pruned pass
DRILL_DOWN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --old-table) OLD_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --new-table) NEW_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --sample-mod) SAMPLE_MOD="${2:?"$1 requires a value"}"; shift 2 ;;
        --from-week) FROM_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --to-week) TO_WEEK="${2:?"$1 requires a value"}"; shift 2 ;;
        --weeks-stride) WEEKS_STRIDE="${2:?"$1 requires a value"}"; shift 2 ;;
        --drill-down) DRILL_DOWN=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database / --old-table / --new-table are interpolated into the reference SQL; require plain ClickHouse identifiers.
for _ident in "$DATABASE" "$OLD_TABLE" "$NEW_TABLE"; do
    [[ "$_ident" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database/--old-table/--new-table must be ClickHouse identifiers (letters, digits, underscore): '$_ident'" >&2; exit 2; }
done
# Numeric args are interpolated into the reference SQL / week arithmetic; require integer shapes so none can alter it.
[[ "$SAMPLE_MOD" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --sample-mod must be a positive integer." >&2; exit 2; }
[[ "$FROM_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --from-week must be a non-negative integer." >&2; exit 2; }
[[ "$WEEKS_STRIDE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --weeks-stride must be a positive integer." >&2; exit 2; }
[[ -z "$TO_WEEK" || "$TO_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --to-week must be a non-negative integer." >&2; exit 2; }
[[ -f "$VERIFY_SQL" ]] || { echo "ERROR: cannot find verify SQL at $VERIFY_SQL" >&2; exit 2; }

ch() {
    clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:verify' --query "$1"
}

log() {
    echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
}

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block from the reference SQL (exact-line markers), and
# substitute this window's placeholders.
render_block() {
    local block="$1" lo="$2" hi="$3" sql
    sql="$(awk -v begin="-- >>> BEGIN $block" -v end="-- >>> END $block" \
        '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$VERIFY_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${OLD_TABLE}'/$OLD_TABLE}"
    sql="${sql//'${NEW_TABLE}'/$NEW_TABLE}"
    sql="${sql//'${WINDOW_LO}'/$lo}"
    sql="${sql//'${WINDOW_HI}'/$hi}"
    sql="${sql//'${SAMPLE_MOD}'/$SAMPLE_MOD}"
    printf '%s' "$sql"
}

# Verdict TSV row for one window: src_rows dst_rows src_checksum dst_checksum ok
compare_window() {
    clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:verify' --multiquery --query "$(render_block compare "$1" "$2")"
}

# Per-key differences for one window (only run on a mismatch, under --drill-down).
drill_down_window() {
    clickhouse-client --database "$DATABASE" --log_comment 'traces_local_v2_cutover:verify' --multiquery --query "$(render_block drill-down "$1" "$2")"
}

ROWS="$(ch "SELECT count() FROM $OLD_TABLE")"
if [[ "$ROWS" == "0" ]]; then
    log "Old-schema table '$OLD_TABLE' is empty — nothing to verify."
    exit 0
fi

# Week range from the old table's created_at (bounded and real; covers rows whose id_at is far-future from the bad-id bug
# but whose created_at is real). Same anchor math as backfill.sh.
ANCHOR="$(ch "SELECT toString(toMonday(min(created_at))) FROM $OLD_TABLE")"
HORIZON="$(ch "SELECT toString(addWeeks(toMonday(max(created_at)), 1)) FROM $OLD_TABLE")"
LAST_WEEK="$(ch "SELECT dateDiff('week', toDate('$ANCHOR'), toDate('$HORIZON')) - 1")"
[[ -n "$TO_WEEK" ]] || TO_WEEK="$LAST_WEEK"

log "Verify: $OLD_TABLE vs $NEW_TABLE | weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE | sample 1/$SAMPLE_MOD"

mismatches=0
checked=0
for (( week=FROM_WEEK; week<=TO_WEEK; week+=WEEKS_STRIDE )); do
    LO="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $week))") 00:00:00"
    HI="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $((week + 1))))") 00:00:00"

    read -r src_rows dst_rows src_checksum dst_checksum ok <<< "$(compare_window "$LO" "$HI")"
    checked=$((checked + 1))
    if [[ "$ok" == "1" ]]; then
        log "week $week ($LO .. $HI): OK (rows=$src_rows)"
    else
        mismatches=$((mismatches + 1))
        log "MISMATCH week $week ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows src_checksum=$src_checksum dst_checksum=$dst_checksum" >&2
        if [[ "$DRILL_DOWN" == "1" ]]; then
            log "  differing keys (key, src_hash, dst_hash; NULL = missing on that side):" >&2
            drill_down_window "$LO" "$HI" >&2
        else
            log "  re-run with --drill-down to list the differing keys for this window" >&2
        fi
    fi
done

if [[ "$mismatches" != "0" ]]; then
    log "FAILED: $mismatches of $checked windows mismatched." >&2
    exit 1
fi
log "PASSED: all $checked windows match (sample 1/$SAMPLE_MOD)."
