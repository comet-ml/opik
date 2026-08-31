#!/usr/bin/env bash
#
# Fidelity QA driver for the buffered traces cutover (runbook: ../README.md, "Verifying the migration").
#
# Compares the migrated data on the old-schema and new-schema tables, week by week (created_at), using a NORMALIZED
# fingerprint so sentinel/precision differences (end_time NULL<->epoch, ttft NULL<->NaN, ns<->us) do not count as
# changes. For each week it reads one (row count, checksum) verdict per side; a mismatch means that week's live, deduped
# content differs. A differing window is then re-checked on the sorting key, which yields one of three outcomes: a real
# MISMATCH, an OK superseded-version artifact, INCONCLUSIVE where a version tie left FINAL free to pick between rows
# that differ, or UNCERTIFIABLE where that tie check could not be read at all. With --drill-down, any differing window is followed by a per-key
# listing. Exits non-zero if any window mismatched OR could not be certified: a gate that cannot decide must not pass.
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
#   --old-table NAME    old-schema table (Nullable, nanosecond). Default traces. After the EXCHANGE: traces_pre_cutover_backup.
#   --new-table NAME    new-schema table (sentinels, microsecond). Default traces_local_v2. After the EXCHANGE: traces.
#                       After a stage B/C ROLLBACK the defaults do not apply at all — traces_local_v2 no longer exists, so a
#                       bare run dies with "Unknown table ... traces_local_v2". The old-schema side is the restored original
#                       (`traces`) and the new-schema side is the parked successor: pass
#                       `--old-table traces --new-table traces_post_rollback_backup`, and expect the cutover window's
#                       week to legitimately mismatch by the post-cutover writes the rollback discarded — stop below it,
#                       using the offset rollback.sh prints (see README "Verifying after a rollback").
#   --sample-mod N      compare a deterministic 1/N id sample (same ids on both sides). Default 1 (every row).
#   --from-week N       start at week offset N (0-based from the anchor Monday). Default 0. An OFFSET, not a date.
#   --to-week M         stop after week offset M (inclusive). Default: last week with data. Also an OFFSET — a YYYYMMDD
#                       partition name is rejected rather than walked as millions of empty windows.
#                       'last-sealed' stops before the current calendar week: a convenience when the compare runs in the
#                       same week as whatever it means to exclude, otherwise pass the offset explicitly.
#   --weeks-stride S    compare every S-th week (S>1 samples partitions for a quick pass). Default 1.
#   --drill-down        on any week the compare reported as differing, print up to 100 keys that differ or exist on one
#                       side only. Not limited to a MISMATCH: the artifact and INCONCLUSIVE verdicts are reached from the
#                       same differing-key set, and those are the ones an operator most often needs to see.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SQL="$SCRIPT_DIR/db-app-analytics/000005_verify_migration.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
OLD_TABLE="traces"          # old-schema side; becomes traces_pre_cutover_backup after the EXCHANGE (see --old-table)
NEW_TABLE="traces_local_v2" # new-schema side (the successor being built); becomes traces after the EXCHANGE
SAMPLE_MOD=1                # 1 = every row; N compares a deterministic 1/N id sample, identical on both sides
FROM_WEEK=0
TO_WEEK=""
WEEKS_STRIDE=1              # 1 = every week; S skips to every S-th weekly partition for a quick, pruned pass
DRILL_DOWN=0
RECEIVE_TIMEOUT=1800        # seconds tolerated between server packets, not total query time. See --receive-timeout.

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
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database / --old-table / --new-table are interpolated into the reference SQL; require plain ClickHouse identifiers.
for _ident in "$DATABASE" "$OLD_TABLE" "$NEW_TABLE"; do
    [[ "$_ident" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database/--old-table/--new-table must be ClickHouse identifiers (letters, digits, underscore): '$_ident'" >&2; exit 2; }
done
# Numeric args are interpolated into the reference SQL / week arithmetic; require integer shapes so none can alter it.
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$SAMPLE_MOD" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --sample-mod must be a positive integer." >&2; exit 2; }
[[ "$FROM_WEEK" =~ ^[0-9]+$ ]] || { echo "ERROR: --from-week must be a non-negative integer." >&2; exit 2; }
[[ "$WEEKS_STRIDE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --weeks-stride must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }
[[ -z "$TO_WEEK" || "$TO_WEEK" == last-sealed || "$TO_WEEK" =~ ^[0-9]+$ ]] \
    || { echo "ERROR: --to-week must be a non-negative integer or 'last-sealed'." >&2; exit 2; }
[[ -f "$VERIFY_SQL" ]] || { echo "ERROR: cannot find verify SQL at $VERIFY_SQL" >&2; exit 2; }

# One place for the connection and client-side options, so the four call sites below cannot drift — in particular so
# --receive-timeout applies to the confirm-keys re-check and the drill-down, not only to the window compare.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --log_comment 'traces_local_v2_cutover:verify' --receive_timeout="$RECEIVE_TIMEOUT")

ch() {
    clickhouse-client "${CH_ARGS[@]}" --query "$1"
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
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$(render_block compare "$1" "$2")"
}

# Per-key differences for one window (only run on a mismatch, under --drill-down).
drill_down_window() {
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$(render_block drill-down "$1" "$2")"
}

# Count of keys in one window that GENUINELY differ, re-checked on the sorting key so FINAL cannot hide a
# version (see the confirm-keys block for why a created_at window can surface a superseded row on one side
# only). 0 means the window's difference is a superseded-version artifact, not a data difference — provided no
# key's newest version is tied, which version_ties_window answers next.
confirm_keys_window() {
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$(render_block confirm-keys "$1" "$2")"
}

# Per side, how many keys in one window have more than one row sharing their newest last_updated_at — i.e. where FINAL
# had no forced winner. Run ONLY when confirm-keys returned 0, because that is the only verdict whose soundness depends
# on it; see the version-ties block for why it is a separate statement and an upper bound.
version_ties_window() {
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$(render_block version-ties "$1" "$2")"
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
# but whose created_at is real). Same anchor math as backfill.sh.
ANCHOR="$(ch "SELECT toString(toMonday(min(created_at))) FROM $OLD_TABLE")"
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

log "Verify: $OLD_TABLE vs $NEW_TABLE | weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE | sample 1/$SAMPLE_MOD"

mismatches=0
artifacts=0
inconclusive=0
uncertifiable=0
checked=0
for (( week=FROM_WEEK; week<=TO_WEEK; week+=WEEKS_STRIDE )); do
    LO="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $week))") 00:00:00"
    HI="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $((week + 1))))") 00:00:00"

    # Capture first (not read <<< "$(...)"): a here-string command substitution is exempt from set -e, so a
    # clickhouse-client failure here would be swallowed, leaving `ok` empty and the week mislabeled as a MISMATCH. A
    # plain assignment IS caught by set -e, so an infra blip aborts with the real error instead of a false fidelity fail.
    compare_out="$(compare_window "$LO" "$HI")"
    read -r src_rows dst_rows src_checksum dst_checksum ok <<< "$compare_out"
    checked=$((checked + 1))
    if [[ "$ok" == "1" ]]; then
        log "week $week ($LO .. $HI): OK (rows=$src_rows)"
    else
        week_certified=0   # set only by the artifact verdict, which differs but passes; gates the drill-down nudge
        # A window difference is not yet a fidelity failure: windowing on created_at under FINAL can surface a
        # SUPERSEDED version on one side only. Re-check the differing keys on the sorting key, where FINAL
        # always sees every version, and let that decide. Plain assignment so set -e catches an infra blip
        # instead of an empty result being read as "artifact" and silently passing a real mismatch.
        unresolved="$(confirm_keys_window "$LO" "$HI")"
        [[ "$unresolved" =~ ^[0-9]+$ ]] || {
            log "FAILED week $week: confirm-keys returned '$unresolved' (expected a count) — treating as a real mismatch." >&2
            unresolved=-1
        }
        if [[ "$unresolved" == "0" ]]; then
            # The artifact reading holds only where FINAL had a forced winner for every key. Ask now, where it decides
            # the verdict, rather than on every differing window.
            ties_out="$(version_ties_window "$LO" "$HI")"
            read -r src_ties dst_ties <<< "$ties_out"
            # Output that is not two counts cannot certify the window, but it is not a tie either: encoding it as one
            # would print a tie diagnosis, and a triage procedure, for what is a client or infrastructure failure.
            if ! [[ "$src_ties" =~ ^[0-9]+$ && "$dst_ties" =~ ^[0-9]+$ ]]; then
                uncertifiable=$((uncertifiable + 1))
                log "UNCERTIFIABLE week $week ($LO .. $HI): version-ties returned '$ties_out', expected two counts." >&2
                log "  The window differs and its re-check found nothing genuinely differing, but whether that verdict is" >&2
                log "  decidable could not be established. This is a read failure, not a version tie." >&2
            elif (( src_ties == 0 && dst_ties == 0 )); then
                artifacts=$((artifacts + 1))
                week_certified=1
                log "week $week ($LO .. $HI): OK — superseded-version artifact (src_rows=$src_rows dst_rows=$dst_rows); every differing key's live row is identical on both sides, and no key's newest version is tied"
            else
                inconclusive=$((inconclusive + 1))
                log "INCONCLUSIVE week $week ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows version_ties=src:$src_ties/dst:$dst_ties" >&2
                log "  Every differing key's live row matched, but this window holds keys whose newest last_updated_at is" >&2
                log "  shared by more than one row, so FINAL chose among them arbitrarily and may have landed on the same" >&2
                log "  row on both sides by luck — including where one side is missing a version. NOT certified either way." >&2
            fi
        else
            mismatches=$((mismatches + 1))
            log "MISMATCH week $week ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows src_checksum=$src_checksum dst_checksum=$dst_checksum genuinely_differing_keys=$unresolved" >&2
            log "  A version tie can also produce this: FINAL may pick different rows for a key whose newest" >&2
            log "  last_updated_at is not unique, on tables whose part layouts differ. See the runbook's triage." >&2
        fi
        if [[ "$DRILL_DOWN" == "1" ]]; then
            log "  differing keys (key, src_hash, dst_hash; NULL = missing on that side):" >&2
            # Non-fatal on purpose: the verdict for this window is already decided above, and since --drill-down now
            # runs on artifact windows too, an unguarded failure here under set -e would abort a run that was passing.
            drill_down_window "$LO" "$HI" >&2 || log "  drill-down failed for week $week; the verdict above stands" >&2
        elif (( week_certified == 0 )); then
            # Only suggested where there is something to investigate. An artifact window differs but passes, so nudging
            # an operator to drill into it would read as an unresolved problem on a clean run.
            log "  re-run with --drill-down to list the differing keys for this window" >&2
        fi
    fi
done

# Fidelity mismatch is the hard failure, and so is a window the re-check could not decide (both exit 1).
if (( mismatches != 0 || inconclusive != 0 || uncertifiable != 0 )); then
    (( mismatches == 0 )) || log "FAILED: $mismatches of $checked windows mismatched." >&2
    if (( uncertifiable != 0 )); then
        log "FAILED: $uncertifiable of $checked windows could not be read to completion — the tie check did not return" >&2
        log "        counts, so those windows are neither certified nor shown to differ. Re-run them." >&2
    fi
    if (( inconclusive != 0 )); then
        log "FAILED: $inconclusive of $checked windows could not be certified — a version tie left FINAL's choice" >&2
        log "        arbitrary on at least one side, so the re-check's 'no genuine difference' cannot be relied on." >&2
        log "        Triage those windows per the runbook. This is neither a mismatch nor a pass." >&2
    fi
    exit 1
fi
if [[ "$checked" == "0" ]]; then
    # An empty range compared nothing, so "all windows match" would be vacuously true — the one answer a fidelity gate
    # must never give. Fail instead: reaching here means the bounds excluded every week, not that the data agrees.
    log "FAILED: no window was compared — weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE selects nothing." >&2
    log "        Nothing was verified, so this is NOT a pass. Widen the range (--from-week/--to-week)." >&2
    exit 1
fi
if [[ "$artifacts" != "0" ]]; then
    log "PASSED: all $checked windows match (weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE, sample 1/$SAMPLE_MOD); $artifacts window(s) held a superseded-version"
    log "        artifact only — a key written more than once lands its stale version in an earlier created_at week on"
    log "        one side. Live data is identical on both sides and no key in those windows had a tied newest version,"
    log "        so the re-check was decisive; nothing to fix (see the confirm-keys block)."
else
    log "PASSED: all $checked windows match (weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE, sample 1/$SAMPLE_MOD)."
fi
