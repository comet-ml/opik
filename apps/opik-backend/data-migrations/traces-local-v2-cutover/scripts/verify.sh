#!/usr/bin/env bash
#
# Fidelity QA driver for the traces cutover (runbook: ../README.md, "Verifying the migration").
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
#   --window-from TS / --window-to TS
#                       compare ONE arbitrary created_at window instead of walking weeks — the shape the post-swap
#                       reconciliation needs, where the range of interest is "the gap", not a calendar week. Half-open
#                       [from, to). 'YYYY-MM-DD HH:MM:SS[.ffffff]', with an optional ' UTC' marker so a value pasted
#                       straight from a driver's RECORD line works; either way BOTH bounds are interpreted as UTC,
#                       matching every other window bound in this runbook.
#                       MUTUALLY EXCLUSIVE with --from-week / --to-week / --weeks-stride, which are 0-based offsets into
#                       a week grid this mode does not build. No new compare SQL is involved: 000005's blocks are already
#                       parameterised by arbitrary ${WINDOW_LO} / ${WINDOW_HI}, and only the driver was generating week
#                       boundaries. --sample-mod, --drill-down, --receive-timeout and the confirm-keys / version-ties
#                       resolution all work unchanged. In this mode the anchor scan is skipped (nothing needs a week
#                       grid), so the run also does not depend on min/max(created_at) holding still.
#                       IT COMPARES TRACES *CREATED* IN THE WINDOW, not every trace touched in it, and the PASSED line
#                       says so. 000005's blocks bound on created_at and have to: the weekly mode's partitions and its
#                       superseded-version artifact logic are created_at-based, and widening the predicate to
#                       `created_at OR last_updated_at` would put one row in two week windows and break both. So a trace
#                       created earlier and merely UPDATED inside the window is not in this compare. That is not a hole
#                       in the reconciliation — those keys are precisely what reconcile.sh's postcondition reports as
#                       stale_keys / payload_mismatch_keys, which is version-driven and sees them where a created_at
#                       window cannot. Read a PASS here as "the traces created in this range are faithful", and the four
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
OLD_TABLE="traces"          # old-schema side; becomes traces_pre_cutover_backup after the EXCHANGE (see --old-table)
NEW_TABLE="traces_local_v2" # new-schema side (the successor being built); becomes traces after the EXCHANGE
SAMPLE_MOD=1                # 1 = every row; N compares a deterministic 1/N id sample, identical on both sides
FROM_WEEK=0
TO_WEEK=""
WEEKS_STRIDE=1              # 1 = every week; S skips to every S-th weekly partition for a quick, pruned pass
DRILL_DOWN=0
RECEIVE_TIMEOUT=1800        # seconds tolerated between server packets, not total query time. See --receive-timeout.
WINDOW_FROM=""              # single arbitrary created_at window, half-open [from, to). See --window-from.
WINDOW_TO=""
# The week bounds carry defaults, so "was it passed?" cannot be read off their values — an explicit --from-week 0 or
# --weeks-stride 1 has to conflict with --window-from just as any other value would, or the mutual exclusion would be
# silently partial exactly where an operator is most likely to combine them by habit.
WEEK_BOUND_FLAGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --old-table) OLD_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --new-table) NEW_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --sample-mod) SAMPLE_MOD="${2:?"$1 requires a value"}"; shift 2 ;;
        --from-week) FROM_WEEK="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --to-week) TO_WEEK="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --weeks-stride) WEEKS_STRIDE="${2:?"$1 requires a value"}"; WEEK_BOUND_FLAGS+=("$1"); shift 2 ;;
        --window-from) WINDOW_FROM="${2:?"$1 requires a value"}"; shift 2 ;;
        --window-to) WINDOW_TO="${2:?"$1 requires a value"}"; shift 2 ;;
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

# Window mode: one arbitrary created_at range instead of a week grid. The two modes cannot be combined — the week bounds
# are offsets into a grid this mode never builds — so refuse rather than silently letting one win.
WINDOW_MODE=0
if [[ -n "$WINDOW_FROM" || -n "$WINDOW_TO" ]]; then
    WINDOW_MODE=1
    [[ -n "$WINDOW_FROM" && -n "$WINDOW_TO" ]] \
        || { echo "ERROR: --window-from and --window-to must be passed together (the window is half-open [from, to))." >&2; exit 2; }
    if (( ${#WEEK_BOUND_FLAGS[@]} > 0 )); then
        echo "ERROR: --window-from/--window-to are mutually exclusive with ${WEEK_BOUND_FLAGS[*]}. Those are 0-based" >&2
        echo "       OFFSETS into a weekly grid anchored on toMonday(min(created_at)); window mode compares one explicit" >&2
        echo "       range and builds no grid at all, so there is nothing for an offset to mean." >&2
        exit 2
    fi
    # Accept the ' UTC' marker the drivers print, so a value pasted from a RECORD line works, but do not require it: the
    # bounds are interpreted as UTC either way (the SQL pins 'UTC'), which is what --sentinel-window-from also does. The
    # consequence of a wrong zone here is a shifted comparison window, not a mutation — unlike the anchors reconcile.sh
    # and rollback.sh take, where the marker is mandatory because a shifted value silently destroys or resurrects rows.
    WINDOW_FROM="${WINDOW_FROM% UTC}"
    WINDOW_TO="${WINDOW_TO% UTC}"
    for _bound in "$WINDOW_FROM" "$WINDOW_TO"; do
        [[ "$_bound" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] \
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
    sql="${sql//'${SAMPLE_MOD}'/$SAMPLE_MOD}"
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

# Verdict TSV row for one window: src_rows dst_rows src_checksum dst_checksum ok
compare_window() {
    local sql
    sql="$(render_block compare "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
}

# Per-key differences for one window (only run on a mismatch, under --drill-down).
# Returns rather than exits on a render failure: the call site treats a failed drill-down as non-fatal, and an exit
# here would abort a run whose verdicts were already decided.
drill_down_window() {
    local sql
    sql="$(render_block drill-down "$1" "$2")" || return 1
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
}

# Count of keys in one window that GENUINELY differ, re-checked on the sorting key so FINAL cannot hide a
# version (see the confirm-keys block for why a created_at window can surface a superseded row on one side
# only). 0 means the window's difference is a superseded-version artifact, not a data difference — provided no
# key's newest version is tied, which version_ties_window answers next.
confirm_keys_window() {
    local sql
    sql="$(render_block confirm-keys "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
}

# Per side, how many keys in one window carry MORE THAN ONE DISTINCT ROW at their newest last_updated_at — i.e. where
# FINAL had to choose between rows that differ. Run ONLY when confirm-keys returned 0, because that is the only verdict
# whose soundness depends on it; see the version-ties block for why it is a separate statement and an upper bound.
version_ties_window() {
    local sql
    sql="$(render_block version-ties "$1" "$2")" || exit 2
    clickhouse-client "${CH_ARGS[@]}" --multiquery --query "$sql"
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
resolve_week_grid() {
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

(( WINDOW_MODE == 1 )) || resolve_week_grid

if (( WINDOW_MODE == 1 )); then
    log "Verify: $OLD_TABLE vs $NEW_TABLE | window [$WINDOW_FROM .. $WINDOW_TO) UTC | sample 1/$SAMPLE_MOD"
else
    log "Verify: $OLD_TABLE vs $NEW_TABLE | weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE | sample 1/$SAMPLE_MOD"
fi

mismatches=0
artifacts=0
inconclusive=0
uncertifiable=0
checked=0
# One window, verdict and all. Factored out so the week grid and the single arbitrary window share EXACTLY this logic —
# the compare, the confirm-keys re-check, the version-ties decision, the drill-down and every verdict counter. A second
# copy for window mode would be a second place for a fidelity verdict to be decided, which is the last thing this file
# should hold two of. $1 labels the window in the log ("week 3", or "window"); $2/$3 are its half-open bounds.
compare_one_window() {
    local label="$1" LO="$2" HI="$3"
    local compare_out src_rows dst_rows src_checksum dst_checksum ok window_certified unresolved ties_out src_ties dst_ties

    # Capture first (not read <<< "$(...)"): a here-string command substitution is exempt from set -e, so a
    # clickhouse-client failure here would be swallowed, leaving `ok` empty and the window mislabeled as a MISMATCH. A
    # plain assignment IS caught by set -e, so an infra blip aborts with the real error instead of a false fidelity fail.
    compare_out="$(compare_window "$LO" "$HI")"
    read -r src_rows dst_rows src_checksum dst_checksum ok <<< "$compare_out"
    checked=$((checked + 1))
    if [[ "$ok" == "1" ]]; then
        log "$label ($LO .. $HI): OK (rows=$src_rows)"
        return 0
    fi
    window_certified=0   # set only by the artifact verdict, which differs but passes; gates the drill-down nudge
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
            log "  NOT certified either way." >&2
        fi
    else
        mismatches=$((mismatches + 1))
        log "MISMATCH $label ($LO .. $HI): src_rows=$src_rows dst_rows=$dst_rows src_checksum=$src_checksum dst_checksum=$dst_checksum genuinely_differing_keys=$unresolved" >&2
        log "  A version tie can also produce this: where a key's newest last_updated_at is carried by more than one" >&2
        log "  DISTINCT row, FINAL may pick a different one per side, the part layouts differing. See the runbook's triage." >&2
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
else
    for (( week=FROM_WEEK; week<=TO_WEEK; week+=WEEKS_STRIDE )); do
        LO="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $week))") 00:00:00"
        HI="$(ch "SELECT toString(addWeeks(toDate('$ANCHOR'), $((week + 1))))") 00:00:00"
        compare_one_window "week $week" "$LO" "$HI"
    done
fi

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
    # Unreachable in window mode, where the from < to check up front guarantees exactly one window; kept unconditional
    # so the guard does not depend on that argument staying true.
    log "FAILED: no window was compared — weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE selects nothing." >&2
    log "        Nothing was verified, so this is NOT a pass. Widen the range (--from-week/--to-week)." >&2
    exit 1
fi
# The PASSED line states the range it covered, so a pass can never be read as broader than it was — the same reason the
# weekly form prints its bounds and stride. In window mode that is the explicit window, which is the whole point of the
# mode: a reconciliation compare has to be quotable as "this exact range was checked".
if (( WINDOW_MODE == 1 )); then
    # "created in" rather than just the range: 000005 bounds on created_at, so a trace created earlier and merely
    # updated inside the window is not in this compare (see --window-from's doc). Saying so on the PASSED line is what
    # stops the pass being quoted as broader than it is — reconcile.sh's stale_keys / payload_mismatch_keys are what
    # cover the updated ones.
    COVERED="traces CREATED in window [$WINDOW_FROM .. $WINDOW_TO) UTC, sample 1/$SAMPLE_MOD"
else
    COVERED="weeks [$FROM_WEEK..$TO_WEEK] stride $WEEKS_STRIDE, sample 1/$SAMPLE_MOD"
fi
if [[ "$artifacts" != "0" ]]; then
    log "PASSED: all $checked windows match ($COVERED); $artifacts window(s) held a superseded-version"
    log "        artifact only — a key written more than once lands its stale version in an earlier created_at week on"
    log "        one side. Live data is identical on both sides and no key in those windows had a tied newest version,"
    log "        so the re-check was decisive; nothing to fix (see the confirm-keys block)."
else
    log "PASSED: all $checked windows match ($COVERED)."
fi
