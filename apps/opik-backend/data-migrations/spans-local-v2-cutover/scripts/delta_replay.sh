#!/usr/bin/env bash
#
# Driver for step 2 of the spans cutover: delta-insert + deletion replay (runbook: ../README.md).
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
#   --max-insert-block-size N  SETTINGS max_insert_block_size for the delta INSERT. Default 65536. Lower than
#                             backfill.sh's, for the reason below.
#   --min-insert-block-size-bytes N
#                             SETTINGS min_insert_block_size_bytes for the delta INSERT. Default 33554432 (32 MiB),
#                             an eighth of backfill.sh's. On spans this is the bound that actually binds -- rows are
#                             wide uncompressed, so the row-count cap above rarely fires first -- and it is the one
#                             dial over peak insert memory. It is tightened here and not in the backfill because the
#                             delta is small: the part-count cost of small blocks is bounded by how little it copies,
#                             while the memory saving applies to a statement that runs inside the cutover window.
#   --max-partitions-per-insert-block N
#                             partitions one insert block of the delta INSERT may span (SETTINGS
#                             max_partitions_per_insert_block). Default 20000; 0 = unlimited. Same correctness gate as in
#                             backfill.sh, and it applies here too: the delta writes into the same weekly-partitioned
#                             shadow, and its `last_updated_at` arm re-copies UPDATES TO OLD ROWS, so a far-future-id row
#                             updated during the window is pulled in and lands in its far-future partition. ClickHouse
#                             defaults this to 100 and aborts the INSERT rather than degrading
#                             (throw_on_max_partitions_per_insert_block = 1). An abort here is worse than in the
#                             backfill: the final delta runs immediately before the EXCHANGE, inside the window the
#                             runbook asks you to keep short.
#
#                             THE DELTA'S PARTITION SPREAD IS WIDER THAN ANY SINGLE BACKFILL WINDOW'S, which is why
#                             this cap is not negotiable. The backfill copies one created_at window at a time; the
#                             delta is everything written since backfill_start, and its `last_updated_at` arm exists
#                             PRECISELY to re-copy updates to OLD rows — so one delta statement can touch far-future,
#                             epoch and ordinary partitions together. Keep it at or above what estimate.sh audit 1
#                             reports, exactly as in backfill.sh.
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
MAX_INSERT_BLOCK_SIZE=65536           # rows per block; an eighth of backfill.sh's (see the option docs above).
MIN_INSERT_BLOCK_SIZE_BYTES=33554432  # bytes per block (32 MiB); the term that bounds peak memory on spans rows.
MAX_PARTITIONS_PER_INSERT_BLOCK=20000 # partitions per block for the delta INSERT; see the option docs above. 0 = unlimited.
MAX_INSERT_THREADS=""                 # threads for the delta INSERT SELECT. EMPTY = inherit the server's setting (the
                                      # line is stripped from the SQL). Explicit 0 FORCES no parallel execution.

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
        --backfill-start) BACKFILL_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-block-size) MAX_INSERT_BLOCK_SIZE="${2:?"$1 requires a value"}"; shift 2 ;;
        --min-insert-block-size-bytes) MIN_INSERT_BLOCK_SIZE_BYTES="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-insert-threads) MAX_INSERT_THREADS="${2:?"$1 requires a value"}"; shift 2 ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2
           echo "Run '$(basename "$0") --help' for the options this driver accepts." >&2
           exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --backfill-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
# The bracket/colon allowance is what makes an IPv6 literal usable (2001:db8::1, or [2001:db8::1]); this is a shape
# guard, not a parser, and it still admits no shell metacharacter, whitespace, quote or slash. --host is passed as its
# own argv element, never interpolated into SQL.
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^\[?[A-Za-z0-9._:-]+\]?$ ]] || { echo "ERROR: --host must be a hostname, IPv4, or IPv6 literal." >&2; exit 2; }
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
[[ "$BACKFILL_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?$ ]] || { echo "ERROR: --backfill-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
[[ "$MAX_INSERT_BLOCK_SIZE" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --max-insert-block-size must be a positive integer." >&2; exit 2; }
[[ "$MIN_INSERT_BLOCK_SIZE_BYTES" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --min-insert-block-size-bytes must be a positive integer." >&2; exit 2; }
# 0 is meaningful (ClickHouse reads it as "unlimited"), so allow it — unlike the bound above. Upper-bounded at 6 digits:
# the setting counts partitions, no real table approaches that, and an out-of-range value would otherwise be rendered
# into the SQL and rejected by the server mid-run instead of here.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }
[[ -z "$MAX_INSERT_THREADS" || "$MAX_INSERT_THREADS" =~ ^(0|[1-9][0-9]?)$ ]] || { echo "ERROR: --max-insert-threads must be 0 (force no parallel INSERT SELECT execution) or 1..99; omit it entirely to inherit the server's setting." >&2; exit 2; }
[[ -f "$SQL_FILE" ]] || { echo "ERROR: cannot find $SQL_FILE" >&2; exit 2; }

# >>> BEGIN partition-scope (OPIK-8607; fence for extracting this block to test edits -- keep the markers)
# Partition-scoping for this driver's deletion replay: derive the partitions the replay's bridged ids resolve to, then
# emit its statement ONCE PER PARTITION instead of once unbounded.
#
# WHY. An unbounded mutation on a ReplicatedMergeTree allocates a block number in EVERY partition, as ephemeral znodes
# written in a SINGLE atomic ZooKeeper `tryMulti`. That request grows with the table's partition COUNT rather than with
# the rows it removes, and past ZooKeeper's `jute.maxbuffer` (1 MB by default) ZK drops the connection and the session
# expires -- so the replay, a mandatory step, cannot run at all. A weekly partition key grows that count every week,
# forever. See db-app-analytics/000002_delete_partition_scope.sql.
#
# KEEP IN STEP WITH delta_replay.sh, exchange_and_wrap.sh, rollback.sh AND reconcile.sh. All four carry this block
# verbatim between these markers, and a gap between the copies is silent: a driver whose copy stopped scoping would
# fail only on a large estate, mid-window. What is duplicated is ONLY the control flow -- the derivation itself is
# single-source SQL in that file, read by all four. The duplication is deliberate and matches ch(), extract() and
# require_rendered(): this directory ships no sourced helpers, so an operator copies ONE script to a bastion and it
# runs.
#
# WHAT IT ALSO DOES. `IN PARTITION` scopes which PARTS the mutation is registered against, so a scoped statement
# rewrites its partition's parts and no others. Measured on a local rehearsal at 43 partitions / 52 parts: the
# unbounded form rewrote all 52 even on a re-run with nothing left to mask, because its cost follows the scope and not
# the matches, while the scoped form rewrote 4 -- exactly the parts of the one partition it named. So N scoped
# statements cost the parts of those N partitions, which is cheaper than unbounded unless the bridged ids span nearly
# every partition. The blocker is what this removes; the saving is a bonus, and it is the reason not to widen the
# scope casually.

SCOPE_SQL="$SCRIPT_DIR/db-app-analytics/000002_delete_partition_scope.sql"

SCOPE_MODE=""          # skip | unbounded | scoped. Set by derive_delete_scope, read by render_scope/expand_scope.
SCOPE_PARTITIONS=()    # the partition values, ascending, when SCOPE_MODE is `scoped`.
SCOPE_WINDOW_END=""    # the instant the derivation bounded its bridge read at; rendered into the replay's own match.
SCOPE_REASON=""        # one line for the operator saying which mode was chosen and why.

# One `-- >>> BEGIN <name>` .. `-- >>> END <name>` block (exact-line markers) out of a text held in a variable. The
# same marker grammar extract() parses out of a file; this reads a string because the callers below splice a block
# back into the surrounding statement text.
scope_extract() {
    awk -v begin="-- >>> BEGIN $2" -v end="-- >>> END $2" '$0 == begin {f = 1; next} $0 == end {f = 0} f' <<<"$1"
}

# Decide this run's scope. $1 = the table the replay MUTATES; $2 = the event_time floor of its bridge match.
#
# Every refusal path here is an EXIT, never a silent fall back to the unbounded form. That is the opposite of the
# choice made for an underivable id, and deliberately so: an id this cannot derive is a documented state whose
# unbounded fallback is correct and merely slow, while a derivation that could not be RUN is an unknown one -- and on
# the estate this exists for, the unbounded form is precisely what does not work. Refusing sends the operator to fix a
# grant or a connection; falling back would send them into the ZooKeeper failure this block prevents.
derive_delete_scope() {
    local target="$1" anchor="$2" row found partition_key expected sql counts bridged underivable partitions p
    SCOPE_MODE="" SCOPE_PARTITIONS=() SCOPE_WINDOW_END="" SCOPE_REASON=""

    [[ -f "$SCOPE_SQL" ]] || { echo "ERROR: cannot find $SCOPE_SQL" >&2; exit 2; }

    # Is the mutation target weekly-partitioned AT ALL? Asked of the server, never assumed, because both answers are
    # load-bearing and they differ per driver: 000002 and 000006 mutate the partitioned successor, while 000004 mutates
    # the RESTORED ORIGINAL `spans`, which has no PARTITION BY. Emitting `IN PARTITION` against that table is not a
    # harmless no-op -- ClickHouse rejects it outright with INVALID_PARTITION_VALUE -- so the rollback would break.
    #
    # count() COMES BACK WITH THE KEY because an empty partition_key and a table that is not there are otherwise the
    # same answer: a zero-row result prints one empty line and exits 0. A wrong --database, a target that does not
    # exist yet, and a user without SHOW on it (system.tables is access-filtered, so it returns no row rather than an
    # error) would all read as "unpartitioned" and take the unbounded branch -- announcing it as correct while sending
    # the statement this exists to avoid.
    row="$(clickhouse-client "${CH_ARGS[@]}" --format TabSeparated \
        --log_comment 'spans_local_v2_cutover:delete_partition_scope:target' \
        --query "SELECT count(), any(partition_key) FROM system.tables
                 WHERE database = '$DATABASE' AND name = '$target'")" || {
        echo "ERROR: could not read the partition key of '$DATABASE.$target'. Refusing to guess: unbounded is the" >&2
        echo "       ZooKeeper failure OPIK-8607 exists to prevent, and scoped against an unpartitioned table is" >&2
        echo "       rejected outright. Fix the connection or the grant on system.tables and re-run." >&2
        exit 2
    }
    IFS=$'\t' read -r found partition_key <<<"$row"
    if [[ "$found" != "1" ]]; then
        echo "ERROR: no table '$DATABASE.$target' is visible on this connection (system.tables matched $found rows)." >&2
        echo "       That is NOT the same as 'unpartitioned', so this refuses rather than falling back to the" >&2
        echo "       unbounded mutation. Check --database, the step's expected topology, and that the user holds" >&2
        echo "       SHOW on the table -- system.tables is access-filtered, so a missing grant hides it silently." >&2
        exit 2
    fi

    # Three answers, not two. An EMPTY key is the legitimate unpartitioned target (the rollback's restored original);
    # the expected weekly key is the partitioned successor; ANYTHING ELSE is refused. That last branch is why this
    # compares the whole expression rather than just looking for `id_at`: against some other id_at-based key the
    # derived values are still UInt32, so there is no INVALID_PARTITION_VALUE to catch it -- ClickHouse registers the
    # mutation against zero matching parts and returns success, and a run of N statements deletes nothing and reports
    # clean. Whitespace is normalised so a re-serialisation change in a future server is cosmetic; a different
    # expression is not, and it must fail loudly because 000002_delete_partition_scope.sql reproduces THIS one and
    # would need editing anyway.
    expected="toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))"
    if [[ -z "$partition_key" ]]; then
        SCOPE_MODE="unbounded"
        SCOPE_REASON="'$target' has no partition key, so the replay runs unbounded -- correct, and on a single-partition table it is also what ZooKeeper sees."
        return 0
    fi
    if [[ "${partition_key//[[:space:]]/}" != "${expected//[[:space:]]/}" ]]; then
        echo "ERROR: '$DATABASE.$target' is partitioned by" >&2
        echo "         $partition_key" >&2
        echo "       but the derivation in $SCOPE_SQL reproduces" >&2
        echo "         $expected" >&2
        echo "       Scoping to values derived from a different expression would name partitions that hold none of the" >&2
        echo "       rows -- a delete that reports success and removes nothing. Update the derivation to match the" >&2
        echo "       table's key, then re-run." >&2
        exit 2
    fi

    sql="$(scope_extract "$(cat "$SCOPE_SQL")" delete-partition-scope)"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${PARTITION_SCOPE_ANCHOR}'/$anchor}"
    # Checked on the extracted text, so a renamed or moved marker is caught here rather than reaching the server as an
    # empty query that exits 0 and leaves the caller reading "0 bridged ids" as a verdict.
    if [[ -z "${sql//[[:space:]]/}" ]] || ! grep -qF 'delete_partition_scope' <<<"$sql"; then
        echo "ERROR: the 'delete-partition-scope' block of $SCOPE_SQL rendered no statement. Expected the exact marker" >&2
        echo "       lines '-- >>> BEGIN delete-partition-scope' and '-- >>> END delete-partition-scope'." >&2
        exit 2
    fi
    if grep -qF '${' <<<"$sql"; then
        echo "ERROR: the 'delete-partition-scope' block of $SCOPE_SQL still holds an unsubstituted \${...} placeholder." >&2
        exit 2
    fi

    counts="$(clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --query "$sql")" || {
        echo "ERROR: the partition-scope derivation failed against '$DATABASE'. Refusing to fall back to the unbounded" >&2
        echo "       mutation: that is the statement that cannot run once the target has enough weekly partitions." >&2
        exit 2
    }
    # Tab-separated, and the third field is legitimately empty when nothing is derivable, so IFS is pinned to a tab --
    # the default IFS would split the partition list across the variables.
    IFS=$'\t' read -r bridged underivable partitions SCOPE_WINDOW_END <<<"$counts"
    if ! [[ "$bridged" =~ ^[0-9]+$ && "$underivable" =~ ^[0-9]+$ ]]; then
        echo "ERROR: the partition-scope derivation returned no usable counts (got: '$counts')." >&2
        exit 2
    fi

    # Nothing bridged means the replay is a PROVABLE no-op: every replay's predicate is an AND over a match against
    # this set. Emitting nothing is not merely an optimisation -- an unbounded DELETE that deletes nothing still locks
    # every partition, so "nothing to replay" would otherwise be the cheapest way to hit the very failure this block
    # prevents.
    if (( bridged == 0 )); then
        SCOPE_MODE="skip"
        SCOPE_REASON="no span-delete events bridged since the anchor, so the replay would match no rows; emitting no statement."
        return 0
    fi
    # All-or-nothing, never per id. A partially derived set is a set the remaining ids' rows are NOT in -- a delete that
    # reports success and silently skips rows.
    if (( underivable > 0 )); then
        SCOPE_MODE="unbounded"
        SCOPE_REASON="$underivable of $bridged bridged id(s) have no exactly derivable partition (at or past the 2300-01-01 DateTime64 ceiling), so the replay runs as ONE unbounded statement -- correct, and slower."
        return 0
    fi

    # Only the scoped form renders the bound, so it is validated only here -- over an EMPTY bridge window the
    # derivation's bare `bridge_window_end` column has no row to carry it and comes back empty, which is correct and
    # unused: that run emits no statement at all. Shape-checked because it is interpolated into SQL, and because an
    # empty value would render a timestamp that parses and silently matches the wrong window.
    if ! [[ "$SCOPE_WINDOW_END" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?$ ]]; then
        echo "ERROR: the partition-scope derivation returned no usable window bound (got: '$SCOPE_WINDOW_END')." >&2
        exit 2
    fi

    read -r -a SCOPE_PARTITIONS <<<"$partitions"
    # Derivable ids with no partitions is a contradiction the derivation cannot produce, so reaching it means the
    # statement and this parser have drifted. Refuse rather than emit nothing, which would look like a clean replay.
    if (( ${#SCOPE_PARTITIONS[@]} == 0 )); then
        echo "ERROR: the partition-scope derivation reported $bridged derivable id(s) and no partitions. Refusing to" >&2
        echo "       run: emitting nothing here would read as a completed replay." >&2
        exit 2
    fi
    for p in "${SCOPE_PARTITIONS[@]}"; do
        # yyyyMMdd, and shape-checked because it is INTERPOLATED into SQL rather than bound -- `IN PARTITION
        # {p:UInt32}` is a ClickHouse syntax error, so there is no bound form to fall back on.
        [[ "$p" =~ ^[0-9]{8}$ ]] || {
            echo "ERROR: the partition-scope derivation returned '$p', which is not a yyyyMMdd partition value." >&2
            exit 2
        }
    done
    SCOPE_MODE="scoped"
    SCOPE_REASON="$bridged bridged id(s) resolve to ${#SCOPE_PARTITIONS[@]} partition(s): ${SCOPE_PARTITIONS[*]}"
    return 0
}

# The replay statement(s) for the scope just derived: one copy of $1 per partition with ${PARTITION_SCOPE} rendered as
# `IN PARTITION <p>`, or a single copy with it rendered empty, or nothing at all. $1 must already have every other
# placeholder substituted.
#
# ${BRIDGE_WINDOW_END} is rendered alongside it, and ONLY in the scoped case. It closes the replay's bridge match at
# the instant the derivation read the bridge, which is what makes "the scope covers everything this statement can
# match" true by construction instead of by timing -- see the derivation's own header. The unbounded form needs no
# such bound and must not carry one: with no partition predicate it is correct over the whole open-ended window, and
# closing it would narrow the pass for nothing.
#
# A missing placeholder is refused rather than tolerated. Without ${PARTITION_SCOPE} a `scoped` run would emit N
# byte-identical UNBOUNDED statements -- N times the failure this block prevents, reported as a successful scoped
# replay; without ${BRIDGE_WINDOW_END} it would emit the silently-skipped-delete shape that bound exists to remove.
render_scope() {
    local block="$1" p bound placeholder
    case "$SCOPE_MODE" in
        skip) return 0 ;;
        unbounded|scoped) ;;
        *) echo "ERROR: render_scope called before derive_delete_scope." >&2; exit 2 ;;
    esac
    for placeholder in '${PARTITION_SCOPE}' '${BRIDGE_WINDOW_END}'; do
        grep -qF "$placeholder" <<<"$block" || {
            echo "ERROR: the deletion-replay statement carries no $placeholder placeholder, so it cannot be scoped." >&2
            echo "       Restore it: \${PARTITION_SCOPE} on its own line immediately after the DELETE's target table," >&2
            echo "       \${BRIDGE_WINDOW_END} inside the bridge match, beside its event_time floor." >&2
            exit 2
        }
    done
    if [[ "$SCOPE_MODE" == "unbounded" ]]; then
        block="${block//'${BRIDGE_WINDOW_END}'/}"
        printf '%s\n' "${block//'${PARTITION_SCOPE}'/}"
        return 0
    fi
    bound="AND event_time < toDateTime64('$SCOPE_WINDOW_END', 6, 'UTC')"
    block="${block//'${BRIDGE_WINDOW_END}'/$bound}"
    for p in "${SCOPE_PARTITIONS[@]}"; do
        printf '%s\n' "${block//'${PARTITION_SCOPE}'/IN PARTITION $p}"
    done
}

# The same expansion done IN PLACE, for a driver that sends a whole reference file rather than one extracted block:
# $1 is the file's text and $2 the marked block inside it, which is replaced by render_scope's output. Everything
# outside the markers -- other statements, every comment -- is passed through untouched, so the file stays the single
# source and only the replay statement is multiplied.
#
# Spliced in bash rather than by handing the replacement to awk. Both `awk -v` and the environment cap a single string
# (128 KiB on Linux), and the replacement is the block repeated once per partition, so a scope of a few dozen
# partitions crosses that. The failure would be an E2BIG from execve, surfacing as a bare "Argument list too long" that
# names nothing. `awk -v` is doubly wrong here anyway: it interprets backslash escapes in the value.
expand_scope() {
    local sql="$1" name="$2" begin="-- >>> BEGIN $2" end="-- >>> END $2" replacement out="" line inblk=0
    local begins ends
    # Exactly one of each marker, checked before anything is spliced. The splice keys on those exact lines, so a
    # DUPLICATED BEGIN inserts the rendered statements twice -- silently doubling every mutation -- and a missing END
    # leaves the rest of the file swallowed into each copy. Neither reaches the server as an error.
    begins="$(grep -cxF -e "$begin" <<<"$sql" || true)"
    ends="$(grep -cxF -e "$end" <<<"$sql" || true)"
    if (( begins != 1 || ends != 1 )); then
        echo "ERROR: the statement to scope holds $begins '$begin' and $ends '$end'; expected one of each." >&2
        exit 2
    fi
    replacement="$(render_scope "$(scope_extract "$sql" "$name")")" || exit 2
    while IFS= read -r line; do
        if [[ "$line" == "$begin" ]]; then
            out+="$line"$'\n'
            # Only when non-empty: the skip case renders nothing, and appending it would leave a stray blank line
            # where the statement used to be.
            [[ -z "$replacement" ]] || out+="$replacement"$'\n'
            inblk=1
            continue
        fi
        [[ "$line" != "$end" ]] || inblk=0
        (( inblk )) || out+="$line"$'\n'
    done <<<"$sql"
    printf '%s' "$out"
}

# Send a rendered statement set to the server. Over STDIN rather than as --query, for the reason expand_scope splices
# in bash: the text grows with the partition count and an argv string carries the same per-string cap, so a scope of a
# few dozen partitions would fail with "Argument list too long" instead of running. stdin has no such cap, and
# clickhouse-client splits and times the statements identically, printing one elapsed figure per statement.
send_scoped_sql() {
    printf '%s\n' "$1" | clickhouse-client "${CH_ARGS[@]}" --time --multiquery
}

# One line to the operator before the replay runs, so the run report says which form was sent and why -- and, when it
# is scoped, how many wall times to expect from --time.
announce_delete_scope() {
    case "$SCOPE_MODE" in
        scoped) echo "Deletion replay scoped to ${#SCOPE_PARTITIONS[@]} partition(s) -- one statement each, over bridge events before $SCOPE_WINDOW_END UTC. $SCOPE_REASON" ;;
        unbounded) echo "Deletion replay runs UNBOUNDED (one statement, every partition locked). $SCOPE_REASON" ;;
        skip) echo "Deletion replay SKIPPED. $SCOPE_REASON" ;;
        *) echo "ERROR: announce_delete_scope called before derive_delete_scope." >&2; exit 2 ;;
    esac
}
# <<< END partition-scope

sql="$(cat "$SQL_FILE")"
sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
sql="${sql//'${BACKFILL_START}'/$BACKFILL_START}"
sql="${sql//'${MAX_INSERT_BLOCK_SIZE}'/$MAX_INSERT_BLOCK_SIZE}"
sql="${sql//'${MIN_INSERT_BLOCK_SIZE_BYTES}'/$MIN_INSERT_BLOCK_SIZE_BYTES}"
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
#
# --format TabSeparated on both scalar reads in this driver: a pretty default from the user's client config would
# wrap this anchor in box-drawing, and it is pasted verbatim into reconcile.sh --gap-start.
DELTA_START="$(clickhouse-client "${CH_ARGS[@]}" --log_comment 'spans_local_v2_cutover:delta_replay' \
    --format TabSeparated --query "SELECT toString(now64(6, 'UTC'))")"
echo "RECORD delta_start=$DELTA_START UTC  (the gap anchor for the POST-SWAP sweep; pass it with the marker:"
echo "       reconcile.sh --gap-start '$DELTA_START UTC')"

# Partition-scope the deletion replay (OPIK-8607). Derived AFTER delta_start is captured and BEFORE the delta INSERT
# runs, which is deliberate on both sides: after, so the anchor the reconciliation needs exists even if this refuses;
# before, so a refusal costs nothing already done. The scope is derived from the same ${BACKFILL_START} floor the
# replay's own bridge match uses, so it names every partition that match can reach and no fewer. The delta INSERT is
# untouched -- an INSERT allocates block numbers only in the partitions it actually writes, so it never had this
# problem.
derive_delete_scope spans_local_v2 "$BACKFILL_START"
announce_delete_scope
sql="$(expand_scope "$sql" deletion-replay)" || exit 2

# --time makes clickhouse-client print each statement's elapsed seconds to stderr (it prints nothing under a bare
# --query). The numbers AFTER the first are the deletion replay's, summing to its wall time, which is one component of
# the final-delta -> EXCHANGE gap: the window whose writes land only on the old table and are swept back after the swap
# by reconcile.sh (OPIK-8238). Without this flag there is no way to record it short of digging in query_log.
#
# RECORD THE REPLAY'S FIGURES DELIBERATELY ON SPANS, where the traces runbook could treat them as a rounding error. The
# replay's resurrection guard reads `spans` by bare `id`, and `spans` has NO id skip index — migration 000088 indexes
# only created_at/last_updated_at, and the id minmax/bloom pair exists on spans_local_v2 (000115) and on `traces`
# (000113) but not here. The bridged set is tiny (retention is off, so these are user-scale cascade deletes), but the
# read is over the whole id column of the source table, so this statement is a first-class component of the
# final-delta -> EXCHANGE gap rather than a footnote to it. Adding the 000113 equivalent to `spans` is deliberately NOT
# part of this cutover: materializing a bloom filter over a table this size is a heavy mutation that would have to run inside
# the very window the procedure asks to keep short.
#
# THE COUNT OF WALL TIMES IS NO LONGER FIXED AT TWO. Since OPIK-8607 the replay is one statement PER PARTITION, so the
# delta-insert's figure comes first and the replay's follows as one figure per scoped statement (or a single one when
# it runs unbounded, or none at all when there was nothing bridged to replay). Their SUM is the replay measurement --
# announce_delete_scope printed the count just above, so the two can be matched up without counting lines.
echo "Statement wall times (seconds, in order: delta-insert, then one per deletion-replay statement):"
send_scoped_sql "$sql"

# The PENDING DELTA: rows the source took while this pass was running, i.e. exactly what a swap issued now would strand
# in the parked backup for reconcile.sh to sweep. Printing it makes convergence something the operator WATCHES rather
# than guesses; before this, nothing showed the gap at all. It is the same predicate the delta itself uses, so it prunes
# on the created_at / last_updated_at minmax skip indexes (migration 000088) and costs a gap-sized read, not a table scan.
#
# It cannot reach 0 while the source is live — that is the whole reason reconciliation happens AFTER the swap, where the
# parked table is frozen and convergence is by construction. Watch it to size the gap and to decide when the tail is as
# tight as it will get, not as a gate.
PENDING="$(clickhouse-client "${CH_ARGS[@]}" --log_comment 'spans_local_v2_cutover:delta_replay:pending' \
    --format TabSeparated --query \
    "SELECT count() FROM $DATABASE.spans
     WHERE created_at >= toDateTime64('$DELTA_START', 6, 'UTC')
        OR last_updated_at >= toDateTime64('$DELTA_START', 6, 'UTC')")"
echo "Pending delta since delta_start: $PENDING row(s) written to 'spans' while this pass ran."
echo "  That is the gap an EXCHANGE issued now would strand in spans_pre_cutover_backup — reconcile.sh sweeps it back"
echo "  after the swap. Re-run this driver to watch it shrink; it will not reach 0 while the source is live."

echo "Delta + deletion replay complete. RECORD delta_start above (reconcile.sh needs it) and the deletion-replay wall"
echo "time (the SUM of every value after the first, one per scoped statement), which sizes the gap it will sweep. Run"
echo "verify.sh before the EXCHANGE."
