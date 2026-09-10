#!/usr/bin/env bash
#
# Driver for step 2 of the traces cutover: delta-insert + deletion replay (runbook: ../README.md).
#
# Reads db-app-analytics/000002_delta_and_deletion_replay.sql (the single source), substitutes the placeholders and runs
# it. Run it after backfill.sh, then verify.sh, then exchange_and_wrap.sh.
#
# It also prints the two numbers the POST-SWAP reconciliation needs: `RECORD delta_start=`, which is the gap anchor
# reconcile.sh sweeps from, and the pending-delta size, which is how much this pass left behind for that sweep. Neither
# is a gate — the gap cannot be closed before the swap, because the source is live — but both make it observable.
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
#   --backfill-start TS        the anchor printed by backfill.sh ("RECORD backfill_start=..."). Required.
#                             Must carry an explicit ' UTC' marker, as the drivers print it; the value is parsed as
#                             UTC, so without it the zone it was captured in is unknown.
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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/db-app-analytics/000002_delta_and_deletion_replay.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
BACKFILL_START=""
MAX_INSERT_BLOCK_SIZE=1048576
MAX_PARTITIONS_PER_INSERT_BLOCK=2000  # partitions per block for the delta INSERT; see the option docs above. 0 = unlimited.
MAX_INSERT_THREADS=""                 # threads for the delta INSERT SELECT. EMPTY = inherit the server's setting (the
                                      # line is stripped from the SQL). Explicit 0 FORCES no parallel execution.

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --backfill-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }

# One place for the connection and client-side options, so every call site below carries the same host, port,
# database, log_comment and receive_timeout, and cannot drift from the others.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
# No --log_comment here: the main call runs 000002, whose two statements set their own log_comment in SETTINGS, and a
# per-query value overrides the session one — so a tag added here would never reach query_log for the statements that
# matter. The driver's own anchor and pending-delta queries carry no SETTINGS clause, so those pass the flag per call.
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT")
[[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start is required (printed by backfill.sh)" >&2; exit 2; }
# Strip the ' UTC' marker the flag is required to carry (see its option doc). For these bounds a wrong zone is worse
# than a wrong shape: the statements parse the anchor as UTC, so one captured elsewhere shifts silently, and a LATER
# value drops rows from the delta and the replay rather than failing.
case "$BACKFILL_START" in
    *" UTC")
        BACKFILL_START="${BACKFILL_START% UTC}"
        # A bare marker strips to empty, which elsewhere means "not supplied" — two meanings for one value, and
        # the later "required" diagnostic would point away from the actual mistake.
        [[ -n "$BACKFILL_START" ]] || { echo "ERROR: --backfill-start has no timestamp before the ' UTC' marker." >&2; exit 2; }
        ;;
    *)
        echo "ERROR: --backfill-start must carry an explicit ' UTC' marker, as the drivers print it:" >&2
        echo "       --backfill-start '<YYYY-MM-DD HH:MM:SS[.ffffff]> UTC'" >&2
        echo "       The value is parsed as UTC; without the marker the zone it was captured in is unknown." >&2
        exit 2
        ;;
esac
[[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
# 0 is meaningful (ClickHouse reads it as "unlimited"), so allow it — unlike the bound above. Upper-bounded at 6 digits:
# the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be rendered
# into the SQL and rejected by the server mid-run instead of here.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
[[ -z "$MAX_INSERT_THREADS" || "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (force no parallel INSERT SELECT execution) or 1..99; omit it entirely to inherit the server's setting." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }

sql="$(cat "$SQL_FILE")"
sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
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
# delta_start: the instant this pass began READING the source. It is the forward reconciliation's GAP_START — the lower
# bound of what reconcile.sh sweeps out of the parked backup after the swap — so it is captured BEFORE the delta INSERT
# and printed with the ' UTC' marker reconcile.sh requires. Captured in UTC because 000006 parses it as UTC; both halves
# of the pair have to agree, exactly as for backfill_start (see 000001's timezone header).
#
# Losing it is not an escalation: widening the gap window is free, because the sweep is mask-honored and idempotent, so
# backfill_start is always a valid fallback. That is deliberately unlike cutover_start, where estimating destroys or
# resurrects data.
DELTA_START="$(clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:delta_replay' \
    --query "SELECT toString(now64(6, 'UTC'))")"
echo "RECORD delta_start=$DELTA_START UTC  (the gap anchor for the POST-SWAP sweep; pass it with the marker:"
echo "       reconcile.sh --gap-start '$DELTA_START UTC')"

# --time makes clickhouse-client print each statement's elapsed seconds to stderr (it prints nothing under a bare
# --query). The SECOND number is the deletion replay's wall time, which is one component of the final-delta ->
# EXCHANGE gap: the window whose writes land only on the old table and are swept back after the swap by reconcile.sh
# (OPIK-8238). Without this flag there is no way to record it short of digging in query_log.
echo "Statement wall times (seconds, in order: delta-insert, deletion-replay):"
clickhouse-client "${CH_ARGS[@]}" --time --multiquery --query "$sql"

# The PENDING DELTA: rows the source took while this pass was running, i.e. exactly what a swap issued now would strand
# in the parked backup for reconcile.sh to sweep. Printing it makes convergence something the operator WATCHES rather
# than guesses; before this, nothing showed the gap at all. It is the same predicate the delta itself uses, so it prunes
# on the created_at / last_updated_at minmax skip indexes (migration 000088) and costs a gap-sized read, not a table scan.
#
# It cannot reach 0 while the source is live — that is the whole reason reconciliation happens AFTER the swap, where the
# parked table is frozen and convergence is by construction. Watch it to size the gap and to decide when the tail is as
# tight as it will get, not as a gate.
PENDING="$(clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:delta_replay:pending' --query \
    "SELECT count() FROM $DATABASE.traces
     WHERE created_at >= toDateTime64('$DELTA_START', 6, 'UTC')
        OR last_updated_at >= toDateTime64('$DELTA_START', 6, 'UTC')")"
echo "Pending delta since delta_start: $PENDING row(s) written to 'traces' while this pass ran."
echo "  That is the gap an EXCHANGE issued now would strand in traces_pre_cutover_backup — reconcile.sh sweeps it back"
echo "  after the swap. Re-run this driver to watch it shrink; it will not reach 0 while the source is live."

echo "Delta + deletion replay complete. RECORD delta_start above (reconcile.sh needs it) and the deletion-replay wall"
echo "time (the second value), which sizes the gap it will sweep. Run verify.sh before the EXCHANGE."
