#!/usr/bin/env bash
#
# Driver for step 2 of the buffered traces cutover: delta-insert + deletion replay (runbook: ../README.md).
#
# Reads db-app-analytics/000002_delta_and_deletion_replay.sql (the single source), substitutes the placeholders and runs
# it. Run it after backfill.sh, then verify.sh, then exchange_and_wrap.sh.
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
#   --backfill-start TS        the anchor printed by backfill.sh ("RECORD backfill_start=..."). Required.
#   --old-table NAME           old-schema source to read from. Default traces. AFTER THE EXCHANGE the names swap, so a
#                             post-swap catch-up is `--old-table traces_pre_cutover_backup --new-table traces`. Without
#                             these the script can only ever run pre-EXCHANGE: it hardcoded both names, so after the swap
#                             it would read the successor and insert into a table that no longer exists.
#   --new-table NAME           new-schema destination to write to. Default traces_local_v2. Same swap applies.
#                             `verify.sh` has carried these two flags from the start; this brings the delta into line, so
#                             the rows a final delta leaves behind (everything written while it ran) and the deletions
#                             deferred past the swap both have a vehicle.
#   --max-insert-block-size N  SETTINGS max_insert_block_size for the delta INSERT. Default 1048576.
#   --max-partitions-per-insert-block N
#                             partitions one insert block of the delta INSERT may span (SETTINGS
#                             max_partitions_per_insert_block). Default 2000; 0 = unlimited. Same correctness gate as in
#                             backfill.sh, and it applies here too: the delta writes into the same weekly-partitioned
#                             shadow, and its `last_updated_at` arm re-copies UPDATES TO OLD ROWS, so a far-future-id row
#                             updated during the window is pulled in and lands in its far-future partition. ClickHouse
#                             defaults this to 100 and aborts the INSERT rather than degrading
#                             (throw_on_max_partitions_per_insert_block = 1). An abort here is worse than in the
#                             backfill: the final delta runs immediately before the EXCHANGE, inside the window the
#                             runbook asks you to keep short. Pass the SAME value used for the backfill.
#   --max-insert-threads N    threads for the delta INSERT SELECT pipeline (SETTINGS max_insert_threads).
#                             OMITTED BY DEFAULT = INHERIT the server's setting (the line is stripped from
#                             the SQL); an explicit 0 FORCES "INSERT SELECT no parallel execution". Same knob, same caveats and same two costs (memory, part
#                             count) as in backfill.sh -- see its option docs for the full diagnosis.
#                             PASS THE SAME VALUE USED FOR THE BACKFILL: the delta writes into the
#                             same table through the same insert path.

set -euo pipefail

OLD_TABLE="traces"          # old-schema side; becomes traces_pre_cutover_backup after the EXCHANGE (see --old-table)
NEW_TABLE="traces_local_v2" # new-schema side (the successor being built); becomes traces after the EXCHANGE

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000002_delta_and_deletion_replay.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
BACKFILL_START=""
MAX_INSERT_BLOCK_SIZE=1048576
MAX_PARTITIONS_PER_INSERT_BLOCK=2000  # partitions per block for the delta INSERT; see the option docs above. 0 = unlimited.
MAX_INSERT_THREADS=""                 # threads for the delta INSERT SELECT. EMPTY = inherit the server's setting (the
                                      # line is stripped from the SQL). Explicit 0 FORCES no parallel execution.

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --old-table) OLD_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --new-table) NEW_TABLE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --backfill-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start is required (printed by backfill.sh)" >&2; exit 2; }
[[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
# 0 is meaningful (ClickHouse reads it as "unlimited"), so allow it — unlike the bound above. Upper-bounded at 6 digits:
# the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be rendered
# into the SQL and rejected by the server mid-run instead of here.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
[[ -z "$MAX_INSERT_THREADS" || "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (force no parallel INSERT SELECT execution) or 1..99; omit it entirely to inherit the server's setting." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }

echo "Reminder: raise databaseAnalytics.asyncInsertBusyTimeoutMaxMs before this step (backend config, not SQL) and"
echo "restore it after the EXCHANGE."

sql="$(cat "$SQL_FILE")"
# Interpolated straight into the reference SQL, so require plain ClickHouse identifiers — same
# guard verify.sh applies to the same three values.
for _ident in "$DATABASE" "$OLD_TABLE" "$NEW_TABLE"; do
    [[ "$_ident" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database/--old-table/--new-table must be ClickHouse identifiers (letters, digits, underscore): '$_ident'" >&2; exit 2; }
done
[[ "$OLD_TABLE" != "$NEW_TABLE" ]] || { echo "ERROR: --old-table and --new-table must differ; '$OLD_TABLE' would copy a table onto itself." >&2; exit 2; }

sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
sql="${sql//'${OLD_TABLE}'/$OLD_TABLE}"
sql="${sql//'${NEW_TABLE}'/$NEW_TABLE}"
sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
sql="${sql//'${MAX_PARTITIONS_PER_INSERT_BLOCK}'/$MAX_PARTITIONS_PER_INSERT_BLOCK}"
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

# Abort unless the SQL text holds exactly one such assignment. $2 is the file name, for diagnostics.
mit_require_one_assignment() {
    local n
    n="$(mit_assignment_lines "$1" | grep -c . || true)"
    if [[ "$n" -ne 1 ]]; then
        echo "ERROR: expected exactly ONE executable line holding nothing but" >&2
        echo "       'max_insert_threads = \${MAX_INSERT_THREADS},' in $2; found $n." >&2
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
        echo "ERROR: \${MAX_INSERT_THREADS} appears $occurrences times in executable lines of $2; expected" >&2
        echo "       exactly once, as the SETTINGS assignment. Rendering rewrites only that one line, so any" >&2
        echo "       other executable occurrence would survive into the statement the server receives." >&2
        echo "       (Occurrences inside '--' or '/* */' comments are ignored and are fine.)" >&2
        return 1
    fi
}

# >>> BEGIN max_insert_threads rendering (fence for extracting this block to test edits -- keep the markers)
# The SETTINGS line this depends on lives in ANOTHER file, so it is validated rather than assumed. The full
# validation runs once at startup (this block runs at top level, so it is reached before any
# statement is sent). The render needs the line's position, so the lines are resolved here.
#
# Comments are masked before matching. A line-anchored match is NOT by itself a check that the assignment is
# executable: an identical line inside a /* */ block carries a trailing comma too, and would otherwise be
# treated as the assignment.
# `|| true` is load-bearing: mit_assignment_lines ends in a pipeline whose grep exits 1 when there is no
# match, so under `set -euo pipefail` this assignment would fail and `set -e` would kill the script HERE --
# before the count check below could call mit_require_one_assignment. The zero case would exit 1 mutely,
# which is the one case that most needs the diagnostic. The >=2 case never had this exposure, because grep
# succeeds there.
mit_line="$(mit_assignment_lines "$sql" || true)"
if [[ "$(grep -c . <<<"$mit_line" || true)" -ne 1 ]]; then
    mit_require_one_assignment "$sql" "$SQL_FILE" || exit 2
    exit 2
fi
if [[ -z "$MAX_INSERT_THREADS" ]]; then
    # Unset means INHERIT: drop the line so the server's own value applies. Rendering an explicit 0 would
    # OVERRIDE it and force the insert serial -- a slowdown, not a no-op.
    sql="$(sed "${mit_line}d" <<<"$sql")"
else
    sql="$(sed "${mit_line}s/\\\${MAX_INSERT_THREADS}/${MAX_INSERT_THREADS}/" <<<"$sql")"
fi
# Post-condition for BOTH paths, on the comment-masked text so a placeholder mentioned in a comment does not
# trip it. No pipe into `grep -q`: it exits on first match, the upstream process takes SIGPIPE, and
# `set -o pipefail` then reports 141, so the guard would skip its own failure branch.
mit_masked="$(mit_mask_comments "$sql" || true)"
if grep -qF '${MAX_INSERT_THREADS}' <<<"$mit_masked"; then
    echo "ERROR: \${MAX_INSERT_THREADS} survives in an executable line of $SQL_FILE after rendering." >&2
    echo "       Refusing to send SQL containing a literal placeholder." >&2
    exit 2
fi
# <<< END max_insert_threads rendering
# --time makes clickhouse-client print each statement's elapsed seconds to stderr (it prints nothing under a bare
# --query). The SECOND number is the deletion replay's wall time — a Go/No-Go acceptance criterion (it must fit inside
# the buffer hold with margin), so without this the operator has no way to record it short of digging in query_log.
echo "Statement wall times (seconds, in order: delta-insert, deletion-replay):"
clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database "$DATABASE" --time --multiquery --query "$sql"

echo "Delta + deletion replay complete. RECORD the deletion-replay wall time above (the second value) — the"
echo "final-delta -> EXCHANGE gap must fit inside the buffer hold. Run verify.sh before the EXCHANGE."
