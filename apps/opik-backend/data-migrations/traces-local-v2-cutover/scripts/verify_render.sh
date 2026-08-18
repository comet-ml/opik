#!/usr/bin/env bash
# Regression check for the max_insert_threads rendering in backfill.sh and delta_replay.sh.
#
# WHY THIS EXISTS. That rendering depends on ONE exact line living in a DIFFERENT file
# (`max_insert_threads = ${MAX_INSERT_THREADS},` in 000001/000002), and every way it can go wrong is SILENT:
# a reformatted line makes the strip a no-op and sends a literal placeholder to the server; a shared line is
# removed together with its neighbours; a missing assignment makes an explicit --max-insert-threads vanish so
# the server quietly uses its own value. The logic is also duplicated across the two drivers, so it can drift.
#
# It runs no SQL and needs no cluster: it extracts each driver's rendering block VERBATIM, between the
# `# >>> BEGIN max_insert_threads rendering` / `# <<< END` markers, and evaluates it against real and
# deliberately-corrupted copies of the SQL. Extracting rather than reimplementing is the point -- a copy of the
# logic here would drift from the drivers and pass while they broke.
#
# Usage:  ./verify_render.sh          # from this directory
# Exit:   0 all cases pass, 1 otherwise. Intended for a human before a cutover, and for reviewers of this dir.

set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

pass=0; fail=0
BEGIN_MARK='# >>> BEGIN max_insert_threads rendering'
END_MARK='# <<< END max_insert_threads rendering'

extract() { # extract <driver>
    awk -v b="$BEGIN_MARK" -v e="$END_MARK" 'index($0,b){f=1} f{print} index($0,e){exit}' "$1"
}

# render <driver> <sqlfile> <threads> -> prints "OK <rendered-setting-or-none>" or "ABORT <first-error-word>"
render() {
    local drv="$1" sqlfile="$2" threads="$3" out
    out="$(
        set -uo pipefail
        BACKFILL_SQL="$sqlfile"; SQL_FILE="$sqlfile"
        sql="$(cat "$sqlfile")"
        MAX_INSERT_THREADS="$threads"
        eval "$(extract "$drv")" || exit $?
        v="$(grep -oE 'max_insert_threads = [0-9]+' <<<"$sql" | head -1 || true)"
        if grep -qF '${MAX_INSERT_THREADS}' <<<"$(grep -v '^[[:space:]]*--' <<<"$sql" || true)"; then
            echo "LEAK"
        else
            echo "OK ${v:-none}"
        fi
    2>/dev/null )" || { echo "ABORT"; return; }
    echo "$out"
}

check() { # check <label> <expected> <actual>
    if [[ "$3" == "$2" ]]; then printf '  PASS  %-52s %s\n' "$1" "$3"; pass=$((pass+1))
    else printf '  FAIL  %-52s got[%s] want[%s]\n' "$1" "$3" "$2"; fail=$((fail+1)); fi
}

for pair in "backfill.sh:db-app-analytics/000001_backfill_traces_local_v2.sql" \
            "delta_replay.sh:db-app-analytics/000002_delta_and_deletion_replay.sql"; do
    drv="${pair%%:*}"; real="${pair#*:}"
    echo "== $drv against $(basename "$real") =="
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

    # 1-2: the happy paths
    check "inherit renders no key"              "OK none" "$(render "$drv" "$real" "")"
    check "explicit 3 renders the setting"      "OK max_insert_threads = 3" "$(render "$drv" "$real" 3)"

    # 3: the SETTINGS line reformatted -- the fixed-string strip would silently no-op
    sed 's/max_insert_threads = ${MAX_INSERT_THREADS}/max_insert_threads  = ${MAX_INSERT_THREADS}/' "$real" > "$tmp/reformatted.sql"
    check "reformatted line aborts (inherit)"   "ABORT" "$(render "$drv" "$tmp/reformatted.sql" "")"
    check "reformatted line aborts (explicit)"  "ABORT" "$(render "$drv" "$tmp/reformatted.sql" 3)"

    # 4: sharing a line with another setting -- deleting the line would drop the neighbour
    sed "s/^\([[:space:]]*\)max_insert_threads = \${MAX_INSERT_THREADS},\$/\1max_insert_threads = \${MAX_INSERT_THREADS}, log_comment = 'x',/" "$real" > "$tmp/shared.sql"
    check "shared line aborts"                  "ABORT" "$(render "$drv" "$tmp/shared.sql" "")"

    # 5: no assignment at all -- an explicit value would otherwise vanish silently
    grep -vE '^[[:space:]]*max_insert_threads = \$\{MAX_INSERT_THREADS\},?[[:space:]]*$' "$real" > "$tmp/missing.sql"
    check "missing assignment aborts (explicit)" "ABORT" "$(render "$drv" "$tmp/missing.sql" 3)"
    check "missing assignment aborts (inherit)"  "ABORT" "$(render "$drv" "$tmp/missing.sql" "")"

    # 6: placeholder present ONLY inside a block comment -- a bare token match would accept this
    { grep -vE '^[[:space:]]*max_insert_threads = \$\{MAX_INSERT_THREADS\},?[[:space:]]*$' "$real"
      echo '/* max_insert_threads = ${MAX_INSERT_THREADS} */'; } > "$tmp/blockcomment.sql"
    check "placeholder only in /* */ aborts"    "ABORT" "$(render "$drv" "$tmp/blockcomment.sql" 3)"

    # 7: two assignments -- ambiguous, must not guess
    awk '/^[[:space:]]*max_insert_threads = \$\{MAX_INSERT_THREADS\},?[[:space:]]*$/{print; print} !/^[[:space:]]*max_insert_threads = \$\{MAX_INSERT_THREADS\},?[[:space:]]*$/{print}' "$real" > "$tmp/dupe.sql"
    check "duplicate assignments abort"         "ABORT" "$(render "$drv" "$tmp/dupe.sql" "")"

    # 8: the post-condition at scale. It runs only when the assignment IS valid, so this file keeps a good
    #    assignment and adds a STRAY placeholder on an executable line: inherit strips the assignment, the
    #    post-condition must then catch the stray. Made large on purpose -- a piped `grep -v | grep -q` guard
    #    returns 141 here under `set -o pipefail` (grep -q exits first, upstream takes SIGPIPE) and would skip
    #    its own failure branch. This asserts the guard is not written that way.
    #    The filler must be EXECUTABLE lines, not `--` comments: comments are removed by the first grep, so
    #    comment filler produces no pipe pressure and a piped guard would pass. The stray comes FIRST so the
    #    downstream `grep -q` exits while the upstream grep still has bulk left to write.
    { cat "$real"; echo 'SELECT 1 WHERE x = ${MAX_INSERT_THREADS};'
      awk 'BEGIN{for(i=0;i<200000;i++) print "SELECT filler;"}' </dev/null; } > "$tmp/stray_big.sql"
    check "stray placeholder at scale aborts"   "ABORT" "$(render "$drv" "$tmp/stray_big.sql" "")"

    # 9: an oversized VALID file must still render normally -- the guards must not fire on size alone.
    #    Kept to a few thousand lines deliberately: the substitution path uses bash's ${var//x/y}, which is
    #    very slow on multi-megabyte strings under the bash 3.2 that ships with macOS. That is a shell
    #    performance cliff, not a correctness problem -- these SQL files are a couple of hundred lines -- so the
    #    case is sized to prove the point without turning this script into a benchmark.
    { cat "$real"; awk 'BEGIN{for(i=0;i<5000;i++) print "-- filler"}' </dev/null; } > "$tmp/valid_big.sql"
    check "oversized valid file still renders"  "OK max_insert_threads = 3" "$(render "$drv" "$tmp/valid_big.sql" 3)"

    rm -rf "$tmp"; trap - EXIT
done

echo
echo "passed=$pass failed=$fail"
[[ "$fail" -eq 0 ]]
