#!/usr/bin/env bash
#
# POST-SWAP reconciliation driver for the traces cutover (runbook: ../README.md).
#
# The cutover loses writes across the swap (OPIK-8238). The last write-copying statement is the delta INSERT in step 2;
# between it and the EXCHANGE completing sit a deletion replay, the operator's go/no-go gap, the topology guards, the
# cluster-wide settle gate and a second full deletion replay. Nothing holds writes across that window — the procedure
# takes no ingestion-path hold (OPIK-8239) — so every trace written to the old `traces` in it is orphaned when that
# table is parked.
#
# Reconciling BEFORE the swap cannot converge — the source is live, so every pass opens a new gap. After the swap the
# parked table is frozen, so this driver's sweep converges by construction, and its postcondition is a gate rather than a
# snapshot. It runs in both directions:
#
#   forward (after a cutover)  sweep traces_pre_cutover_backup   -> the live successor, then re-apply bridged deletes.
#   reverse (after a rollback) sweep traces_post_rollback_backup -> the restored original, then re-run the reverse replay.
#
# The direction is derived from the live topology, the way finalize.sh detects which backup is parked; there is no
# --direction flag to get wrong. It refuses on an ambiguous or absent estate, and — because every statement it issues is
# shard-local while finalize.sh drops the parked backup ON CLUSTER — on a multi-shard or unreadable topology unless the
# scope is asserted with --confirm-single-shard. See assert_shard_scope.
#
# Idempotent and re-runnable: it reads the postcondition BEFORE mutating anything, so a second run on a reconciled estate
# issues no statement and exits 0.
#
# The SQL is NOT duplicated here. The sweeps and the forward deletion replay come from
# db-app-analytics/000006_post_swap_reconciliation.sql, the postcondition from
# db-app-analytics/000006_verify_reconciliation.sql (its gate blocks plus the advisory leak-check-forward), the settle
# gate from 000003_exchange_and_wrap.sql's three settle-* blocks — shared with exchange_and_wrap.sh, each driver
# rendering its own table scope — and the reverse replay from 000004_rollback_reverse_replay.sql plus its postcondition
# 000004_rollback_verify_replay.sql, both unchanged. The forward replay is a POST-swap statement of its own rather than a
# re-run of 000002's pre-swap one: it reads its resurrection guard from the frozen backup, and carries a third arm that
# pre-swap has no counterpart for. See that block's header.
#
# Connection: CLICKHOUSE_USER / CLICKHOUSE_PASSWORD from the environment, plus --host and --port. CLICKHOUSE_PORT is
# NOT honored by clickhouse-client, and CLICKHOUSE_HOST is honored only when no connection flag is given, so pass
# --host and --port together. The user must be able to set `log_comment` (used for cutover attribution in
# query_log): a `readonly = 1` profile rejects it outright ("Cannot modify 'log_comment' setting in readonly mode"),
# so a read-only assessor needs `readonly = 2` and the migration user needs a non-readonly profile.
#
# PRIVILEGES: the forward path MUTATES THE LIVE NAME, which the rest of the forward cutover does not. On top of the
# forward grant set it needs INSERT and ALTER UPDATE(_row_exists) on `traces` (or `traces_local` on a wrapped estate) and
# SELECT on `traces_pre_cutover_backup`. See the runbook's privileges table — that widening is deliberate and is called
# out there rather than buried here.
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
#                             than total query time. Trade-off and shared rationale: ../README.md.
#   --gap-start TS            what to COPY: the lower bound of the gap window, matched on created_at OR last_updated_at.
#                             FORWARD it is REQUIRED and is the start of the last delta pass — the `RECORD delta_start=`
#                             line delta_replay.sh prints. REVERSE it defaults to --cutover-start and rarely needs
#                             passing. Must carry an explicit ' UTC' marker, as the drivers print it.
#                             WIDENING IT IS FREE: the sweep is mask-honored and idempotent, so `backfill_start` is
#                             always a valid fallback and a lost delta_start never forces an escalation.
#   --cutover-start TS        REVERSE only, and required there: the `RECORD cutover_start=` value exchange_and_wrap.sh
#                             printed. It bounds the reverse deletion replay this driver re-runs after the sweep, exactly
#                             as it does in rollback.sh. Rejected in the forward direction, which replays nothing from it.
#   --swap-done TS            what NOT to RESURRECT: the instant the swap statement RETURNED — `RECORD exchange_done=`
#                             (forward) or `RECORD promote_done=` (reverse). Required. Bridged deletes at or after it are
#                             excluded from the sweep, so a gap-window trace deleted after the swap is not brought back.
#                             UNLIKE --gap-start THIS IS NOT FREE TO GUESS, and the two directions of error are not
#                             symmetric — WHEN IN DOUBT, GUESS EARLY:
#                               * too EARLY excludes too much, so a legitimately live key stays missing and the
#                                 postcondition FAILS LOUDLY. Widen and re-run.
#                               * too LATE excludes too little: a delete that fired AFTER the swap is bridged below the
#                                 bound, the key is live in the frozen backup, the sweep re-inserts it and the replay's
#                                 resurrection guard spares it. The delete is undone, and nothing reports it.
#                             If the printed value was lost, use `cutover_start` — earlier than the swap by
#                             construction, i.e. on the side that fails loudly. Do NOT round it up "to be safe": the
#                             recorded value already trails the swap (../README.md, "The final cutover window"), so
#                             every second added is a second of silent resurrection window.
#   --slack-seconds N         widen --gap-start DOWNWARD by N seconds (default 300) to absorb cross-replica clock skew on
#                             the server-side timestamp defaults the gap window matches on. Free, per the widening note
#                             above. Pass 0 to use the anchor exactly as given.
#   --max-passes N            give up after N sweep -> replay -> postcondition passes (default 3). A pass whose
#                             postcondition is still non-zero is retried, because concurrent traffic can add to the gap
#                             while the sweep runs; a run that exhausts the passes FAILS rather than reporting progress.
#   --max-partitions-per-insert-block N
#                             partitions one insert block of the sweep may span (SETTINGS
#                             max_partitions_per_insert_block). Default 2000; 0 = unlimited. Same correctness gate as in
#                             backfill.sh / delta_replay.sh — the forward sweep writes into the same weekly-partitioned
#                             successor, and far-future UUIDv7 ids reach ClickHouse's default of 100, which ABORTS the
#                             INSERT (throw_on_max_partitions_per_insert_block = 1). Pass the value used for the backfill.
#   --report-only             read the postcondition and stop. Issues NO mutation, in either direction. Exits 0 only when
#                             the gate is already clean — a report that finds a gap is not a pass.
#   --confirm-reimport-successor-writes
#                             REVERSE only, and required there unless --report-only. The reverse sweep re-imports exactly
#                             the post-cutover writes that --accept-post-cutover-write-loss acknowledged discarding, so
#                             this asserts the operator now WANTS them back. That is right when the rollback was
#                             motivated by latency, merge load or the wrap; it is wrong when the successor's CONTENT is
#                             what is suspect, since it re-imports the very data the rollback existed to discard.
#   --confirm-single-shard    assert the run's SHARD SCOPE yourself, the same shape and the same name as in rollback.sh.
#                             Needed in two states, because every statement here and the forward postcondition are
#                             shard-local while finalize.sh drops the parked backup ON CLUSTER:
#                               * the shard count is unreadable or 0 (unknown topology) — you assert it is one shard;
#                               * FORWARD on a cluster reporting more than one shard — you accept that this run covers
#                                 the connected shard alone, and will repeat it on every shard before finalize.sh.
#                             It does NOT unlock the REVERSE direction on more than one shard: the replay there is
#                             shard-local while its postcondition reads every shard, so no single run can satisfy it.
#   --confirm-retention-paused
#                             REQUIRED unless --report-only, in BOTH directions, the same assertion
#                             exchange_and_wrap.sh and rollback.sh already take. Retention deletes bypass the deletion
#                             bridge, so the sweep cannot know about them: a retention delete that fires AFTER the
#                             parked table froze leaves its row masked on the live table, still LIVE in the frozen
#                             backup and absent from the bridge — so the sweep re-inserts it and the deletion replay,
#                             which only re-applies BRIDGED keys, does not mask it again. The trace comes back.
#                             It is not confined to old data, which is the tempting reason to dismiss it: retention
#                             selects by `id` range (UUIDv7), while the gap window matches created_at OR
#                             last_updated_at — and the merge path stamps a fresh last_updated_at while preserving
#                             created_at, so an OLD trace updated during the gap window sits inside the sweep's window
#                             and inside retention's id range at once.
#   --settle-timeout N        seconds the replication-settle gate polls before giving a verdict. Default 120, capped at
#                             3600; 0 takes a single sample. Unlike the pre-swap gate this one sits AFTER the swap, so
#                             its wait costs no cutover tail — but every second is a second the gap-window traces are
#                             still absent from live reads, so it is not free either. Note it is spent PER GATE: once
#                             before the first postcondition read and once per pass, so the worst case is
#                             (1 + --max-passes) x this value, and only on a queue that stays busy-but-not-stuck (a
#                             drained queue returns immediately). See assert_replication_settled for the verdicts.
#   --force                   skip the replication-settle gate. By default the run aborts while a replica is still
#                             applying a mutation to the PARKED table or its replication queue is genuinely stuck,
#                             because the sweep reads that table mask-honored and the postcondition reads ONE replica
#                             (it cannot use clusterAllReplicas — a Replicated table returns a copy per replica, which
#                             would multiply both sides of its join). Use only if settlement is confirmed out of band.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/db-app-analytics"
SWEEP_SQL="$SQL_DIR/000006_post_swap_reconciliation.sql"
VERIFY_SQL="$SQL_DIR/000006_verify_reconciliation.sql"
REVERSE_REPLAY_SQL="$SQL_DIR/000004_rollback_reverse_replay.sql"
REVERSE_VERIFY_SQL="$SQL_DIR/000004_rollback_verify_replay.sql"
# The settle gate's three blocks are shared with exchange_and_wrap.sh, which reads the same statements with its own
# pre-swap table scope — see that file's header. Same hazard, same SQL, different scope.
SETTLE_SQL="$SQL_DIR/000003_exchange_and_wrap.sql"

DATABASE=""
CH_HOST=""                # host; empty = clickhouse-client default/env. See --host.
CH_PORT=""                # native port; empty = clickhouse-client default (9000). See --port.
RECEIVE_TIMEOUT=1800      # seconds tolerated between server packets, not total query time. See --receive-timeout.
GAP_START=""
CUTOVER_START=""
SWAP_DONE=""
SLACK_SECONDS=300
MAX_PASSES=3
MAX_PARTITIONS_PER_INSERT_BLOCK=2000
REPORT_ONLY=0
CONFIRM_REIMPORT=0
CONFIRM_SINGLE_SHARD=0
CONFIRM_RETENTION_PAUSED=0
FORCE=0
SETTLE_TIMEOUT=120        # seconds the settle gate polls before deciding. See --settle-timeout.
SETTLE_TIMEOUT_MAX=3600   # its accepted ceiling; the validation below explains why the check is lexical.

# Stuck-ness thresholds for the replication queue, and the poll interval. Deliberately not flags, and the same values
# exchange_and_wrap.sh uses: they describe what "a replica is genuinely lagging" means, not a per-run choice.
SETTLE_POLL_SECONDS=5
SETTLE_STUCK_AGE_SECONDS=60
SETTLE_STUCK_NUM_TRIES=3

DIRECTION=""              # forward | reverse, derived from the live topology
LIVE_TABLE=""             # traces, or traces_local on a wrapped estate
PARKED_TABLE=""           # traces_pre_cutover_backup | traces_post_rollback_backup
EFFECTIVE_GAP_START=""    # --gap-start widened downward by --slack-seconds, computed server-side
SHARD_SCOPE_NOTE=""       # set by assert_shard_scope when the run is knowingly shard-local; qualifies the verdict
# The 000006 blocks this direction mutates with, IN THE ORDER THEY MUST RUN. Forward: sweep first, then the replay, so
# bridged deletes win over anything the sweep just re-inserted. Reverse has no replay block here — it re-runs 000004's
# unchanged. Set by detect_direction, and an array rather than a string so no IFS or globbing surprise can reorder or
# drop a block whose order is load-bearing.
MUTATING_BLOCKS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="${2:?"$1 requires a value"}"; shift 2 ;;
        --gap-start) GAP_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --cutover-start) CUTOVER_START="${2:?"$1 requires a value"}"; shift 2 ;;
        --swap-done) SWAP_DONE="${2:?"$1 requires a value"}"; shift 2 ;;
        --slack-seconds) SLACK_SECONDS="${2:?"$1 requires a value"}"; shift 2 ;;
        --settle-timeout) SETTLE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-passes) MAX_PASSES="${2:?"$1 requires a value"}"; shift 2 ;;
        --max-partitions-per-insert-block) MAX_PARTITIONS_PER_INSERT_BLOCK="${2:?"$1 requires a value"}"; shift 2 ;;
        --report-only) REPORT_ONLY=1; shift ;;
        --confirm-reimport-successor-writes) CONFIRM_REIMPORT=1; shift ;;
        --confirm-single-shard) CONFIRM_SINGLE_SHARD=1; shift ;;
        --confirm-retention-paused) CONFIRM_RETENTION_PAUSED=1; shift ;;
        --force) FORCE=1; shift ;;
        --host) CH_HOST="${2:?"$1 requires a value"}"; shift 2 ;;
        --port) CH_PORT="${2:?"$1 requires a value"}"; shift 2 ;;
        --receive-timeout) RECEIVE_TIMEOUT="${2:?"$1 requires a value"}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and the three anchors are interpolated into the reference SQL; validate their shapes so none can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CH_HOST" || "$CH_HOST" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "ERROR: --host must be a hostname or IP." >&2; exit 2; }
[[ -z "$CH_PORT" || "$CH_PORT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --port must be a positive integer." >&2; exit 2; }
[[ "$RECEIVE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: --receive-timeout must be a positive integer (seconds)." >&2; exit 2; }
[[ "$SLACK_SECONDS" =~ ^[0-9]+$ ]] || { echo "ERROR: --slack-seconds must be a non-negative integer." >&2; exit 2; }
# Digit-capped BEFORE any arithmetic sees it, for the reason exchange_and_wrap.sh's identical check spells out: a leading
# zero reads as octal, and past 2^63 bash arithmetic wraps silently, which would make the gate's `polls` negative, run
# its loop zero times, and pass a verdict on unset (0) counters without having read replication at all.
[[ "$SETTLE_TIMEOUT" =~ ^(0|[1-9][0-9]{0,3})$ ]] && (( SETTLE_TIMEOUT <= SETTLE_TIMEOUT_MAX )) || { echo "ERROR: --settle-timeout must be an integer between 0 and $SETTLE_TIMEOUT_MAX seconds, with no leading zero; 0 takes a single sample." >&2; exit 2; }
[[ "$MAX_PASSES" =~ ^[1-9][0-9]?$ ]] || { echo "ERROR: --max-passes must be 1..99." >&2; exit 2; }
# 0 is meaningful (ClickHouse reads it as "unlimited"). Upper-bounded at 6 digits for the same reason as in backfill.sh:
# an out-of-range value would otherwise be rendered into the SQL and rejected by the server mid-run instead of here.
[[ "$MAX_PARTITIONS_PER_INSERT_BLOCK" =~ ^(0|[1-9][0-9]{0,5})$ ]] || { echo "ERROR: --max-partitions-per-insert-block must be 0 (unlimited) or 1..999999." >&2; exit 2; }

for _f in "$SWEEP_SQL" "$VERIFY_SQL" "$REVERSE_REPLAY_SQL" "$REVERSE_VERIFY_SQL" "$SETTLE_SQL"; do
    [[ -f "$_f" ]] || { echo "ERROR: cannot find $_f" >&2; exit 2; }
done

# Strip the ' UTC' marker every anchor flag is required to carry, exactly as the other drivers do. For these bounds a
# wrong zone is worse than a wrong shape: the statements parse the value as UTC, so one captured elsewhere shifts
# silently rather than failing.
strip_utc_marker() {
    local flag="$1" value="$2"
    case "$value" in
        "") printf '%s' ""; return 0 ;;
        *" UTC")
            value="${value% UTC}"
            # A bare marker strips to empty, which elsewhere means "not supplied" — two meanings for one value, and the
            # later "required" diagnostic would point away from the actual mistake.
            [[ -n "$value" ]] || { echo "ERROR: $flag has no timestamp before the ' UTC' marker." >&2; exit 2; }
            ;;
        *)
            echo "ERROR: $flag must carry an explicit ' UTC' marker, as the drivers print it:" >&2
            echo "       $flag '<YYYY-MM-DD HH:MM:SS[.ffffff]> UTC'" >&2
            echo "       The value is parsed as UTC; without the marker the zone it was captured in is unknown." >&2
            exit 2
            ;;
    esac
    [[ "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] \
        || { echo "ERROR: $flag must be 'YYYY-MM-DD HH:MM:SS[.ffffff] UTC'." >&2; exit 2; }
    printf '%s' "$value"
}

# `|| exit 2` is not redundant belt-and-braces: strip_utc_marker's own `exit` runs in the command substitution's
# SUBSHELL, so it ends that subshell rather than the script. The assignment then carries the failed status and `set -e`
# does abort — but only for as long as these stay plain assignments. Making the refusal explicit here means a later
# `local`, an `||` or a `readonly` in front of one of them cannot silently turn a rejected anchor into an empty one.
GAP_START="$(strip_utc_marker --gap-start "$GAP_START")" || exit 2
CUTOVER_START="$(strip_utc_marker --cutover-start "$CUTOVER_START")" || exit 2
SWAP_DONE="$(strip_utc_marker --swap-done "$SWAP_DONE")" || exit 2

[[ -n "$SWAP_DONE" ]] || {
    echo "ERROR: --swap-done is required. It is the instant the swap statement RETURNED — 'RECORD exchange_done=' after a" >&2
    echo "       cutover, 'RECORD promote_done=' after a rollback — and it is what stops the sweep resurrecting a trace" >&2
    echo "       deleted after the swap. If the printed value was lost, pass cutover_start instead: it is earlier than" >&2
    echo "       the swap by construction, and erring early makes the postcondition fail loudly rather than resurrect." >&2
    exit 2
}

# One place for the connection and client-side options, so every call site below carries the same host, port, database
# and receive_timeout, and cannot drift. No --log_comment here: every statement this driver runs sets its own in a
# trailing SETTINGS clause, and a per-query value beats a client-level one, so a flag here would never reach query_log.
CH_ARGS=()
[[ -z "$CH_HOST" ]] || CH_ARGS+=(--host "$CH_HOST")
[[ -z "$CH_PORT" ]] || CH_ARGS+=(--port "$CH_PORT")
CH_ARGS+=(--database "$DATABASE" --receive_timeout="$RECEIVE_TIMEOUT")

# --format TabSeparated is explicit, not redundant: clickhouse-client takes a default format from the user's own client
# config, and a pretty/bordered default would put headers and box-drawing into every scalar read below. Those are parsed
# as numbers and timestamps, so the failure would not be an error — it would be a wrong verdict.
ch() {
    clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:reconcile' \
        --format TabSeparated --query "$1"
}

# Same connection, but rendered for a human — used only for the settle gate's detail blocks, whose interesting columns
# are free text (last_exception, postpone_reason, latest_fail_reason) that must not be parsed.
ch_vertical() {
    clickhouse-client "${CH_ARGS[@]}" --log_comment 'traces_local_v2_cutover:reconcile' \
        --format Vertical --query "$1"
}

# Single scalar (empty string if the object does not exist).
traces_engine() {
    ch "SELECT engine FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}
traces_endtime_type() {
    ch "SELECT type FROM system.columns WHERE database = '$DATABASE' AND table = '$1' AND name = 'end_time'"
}

# The live table this run reads, writes and mutates. On a wrapped estate `traces` is a Distributed wrapper: it accepts
# INSERT but REJECTS mutations (code 36/48), so the deletion replay after the sweep would fail. Resolve ONE name the way
# databaseAnalyticsDataModel.tracesDistributedWrapEnabled does in TraceDAO — traces_local when `traces` is Distributed —
# and use it for every statement, so a wrapped estate stays reconcilable. Writing the shard directly is correct rather
# than a bypass of the Distributed sharding key: the parked backup is itself a per-shard table, so its rows already
# belong to the shard this run is connected to.
resolve_live_table() {
    local engine
    engine="$(traces_engine traces)"
    [[ -n "$engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }
    if [[ "$engine" == "Distributed" ]]; then
        LIVE_TABLE="traces_local"
        [[ -n "$(traces_engine traces_local)" ]] || {
            echo "ERROR: 'traces' is a Distributed wrapper but 'traces_local' (the shard it fronts) does not exist, so" >&2
            echo "       there is no table to reconcile. The topology is not a clean post-wrap state — resolve by hand." >&2
            exit 1
        }
    else
        LIVE_TABLE="traces"
    fi
}

# Direction from the live topology, the same signals finalize.sh classifies on: which backup is parked, plus the
# end_time nullability that says which schema each table carries. Presence of a name is convention; the schema is proof.
detect_direction() {
    local pre_cutover post_rollback_end_time live_end_time
    pre_cutover="$(traces_engine traces_pre_cutover_backup)"
    post_rollback_end_time="$(traces_endtime_type traces_post_rollback_backup)"
    live_end_time="$(traces_endtime_type "$LIVE_TABLE")"

    if [[ -n "$pre_cutover" && -n "$post_rollback_end_time" ]]; then
        echo "ERROR: both 'traces_pre_cutover_backup' and 'traces_post_rollback_backup' exist, so the direction is" >&2
        echo "       ambiguous — a clean estate never holds both. Refusing rather than guessing which way to sweep;" >&2
        echo "       resolve the estate by hand (finalize.sh refuses the same state, for the same reason)." >&2
        exit 1
    fi

    if [[ -n "$pre_cutover" ]]; then
        DIRECTION="forward"
        PARKED_TABLE="traces_pre_cutover_backup"
        MUTATING_BLOCKS=(forward-sweep forward-deletion-replay)
        [[ "$live_end_time" != Nullable* ]] || {
            echo "ERROR: 'traces_pre_cutover_backup' is parked, but the live '$LIVE_TABLE' still has a Nullable end_time —" >&2
            echo "       i.e. it is the ORIGINAL schema, so the EXCHANGE has not run (or has been rolled back) while a" >&2
            echo "       pre-cutover backup name survives. There is no forward gap to sweep from that state. Resolve the" >&2
            echo "       estate by hand." >&2
            exit 1
        }
        return 0
    fi

    if [[ -n "$post_rollback_end_time" ]]; then
        [[ "$post_rollback_end_time" != Nullable* ]] || {
            echo "ERROR: 'traces_post_rollback_backup' exists but carries the ORIGINAL schema (Nullable end_time), so it is" >&2
            echo "       not the successor a rollback parks there. The name is convention; the schema is the proof, and it" >&2
            echo "       disagrees. Refusing — resolve by hand." >&2
            exit 1
        }
        DIRECTION="reverse"
        PARKED_TABLE="traces_post_rollback_backup"
        MUTATING_BLOCKS=(reverse-sweep)
        [[ "$live_end_time" == Nullable* ]] || {
            echo "ERROR: the successor is parked as 'traces_post_rollback_backup', but the live '$LIVE_TABLE' is not the" >&2
            echo "       restored ORIGINAL (its end_time is '${live_end_time:-<absent>}', not Nullable). A promote leaves" >&2
            echo "       the original live; this estate does not match, so the reverse sweep has no valid target." >&2
            exit 1
        }
        return 0
    fi

    # Neither parked name exists. Before refusing, name the one state that is recoverable in one command: the forward
    # EXCHANGE committed but its post-swap RENAME did not, so the parked original is still sitting under traces_local_v2.
    # exchange_and_wrap.sh and rollback.sh print the same remediation.
    if [[ "$live_end_time" != Nullable* && -n "$(traces_engine traces_local_v2)" ]]; then
        echo "ERROR: the EXCHANGE ran (the live '$LIVE_TABLE' holds the successor schema) but 'traces_local_v2' still" >&2
        echo "       exists — the post-swap RENAME did not complete, so the parked original is under the wrong name and" >&2
        echo "       there is nothing this driver can sweep from. Finish that RENAME, then re-run this command:" >&2
        echo "         clickhouse-client ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} --database $DATABASE --query \"RENAME TABLE $DATABASE.traces_local_v2 TO $DATABASE.traces_pre_cutover_backup ON CLUSTER '{cluster}'\"" >&2
        exit 1
    fi
    echo "ERROR: no parked backup found — neither 'traces_pre_cutover_backup' nor 'traces_post_rollback_backup' exists." >&2
    echo "       Reconciliation sweeps FROM the frozen parked table, so there is nothing to reconcile against. Either no" >&2
    echo "       cutover has run here, or finalize.sh has already retired the backup — and finalize refuses without" >&2
    echo "       --confirm-gap-reconciled precisely so that cannot happen before this driver has run." >&2
    exit 1
}

# Pre-verify gate, and a pre-SWEEP one. Two distinct reasons, both needing this replica to hold what the others do:
#
#   * the postcondition reads ONE replica by necessity (it joins two sides, and clusterAllReplicas would return a full
#     copy of each per replica and multiply both), so a part this replica has not fetched reads as a missing key;
#   * the sweep READS THE PARKED TABLE mask-honored. A user delete issued against the old table just before the swap is
#     an ordinary asynchronous mutation — no lightweight_deletes_sync there, unlike the cutover's own replays — so if it
#     has not applied on the replica the sweep reads, the sweep sees the row as live and copies a deleted trace back.
#
# It reuses 000003's three settle-* blocks with a POST-SWAP scope, and the verdict differs from the pre-swap one by
# exactly what the swap changed about which table is quiet:
#
#   * unfinished MUTATIONS: scoped to the PARKED table and judged UNCONDITIONALLY, within --settle-timeout. That table
#     is frozen, so anything still applying to it is a pre-swap delete mid-flight — the second hazard above — and it
#     drains rather than recurring. The LIVE table is deliberately NOT in this scope: after the swap it takes user
#     deletes continuously, each an ordinary asynchronous mutation, so requiring zero there would abort on healthy
#     traffic and push the operator to --force. Nor is it a hazard: a live-table delete still applying leaves the row
#     visible, which the postcondition reads as present — never as missing — and bridged deletes are re-applied by the
#     replay, which carries lightweight_deletes_sync = 2 of its own.
#   * the replication QUEUE: scoped to BOTH tables, because either one short of a part on this replica skews the
#     postcondition's join, and judged on STUCK-NESS rather than depth for the same reason the pre-swap gate does —
#     under live ingestion a GET_PART entry exists for every part not yet fetched, so an instantaneous zero is not a
#     healthy-cluster property. It passes the moment the queue drains; failing that, an entry aged past
#     SETTLE_STUCK_AGE_SECONDS, more retries than SETTLE_STUCK_NUM_TRIES, or any last_exception is a genuinely lagging
#     replica and fails the gate naming the offending entries.
 # KEEP IN STEP WITH exchange_and_wrap.sh's assert_replication_settled. The two drivers run the SAME GATE on opposite
# sides of the swap, and the half that decides a verdict — the three settle-* blocks — is already shared, from
# 000003_exchange_and_wrap.sql, each driver rendering its own table scope. What is duplicated is the control flow around
# it, and these parts MUST NOT DRIFT: the stuck thresholds and the poll interval, the requirement that the sample be
# SEVEN NUMERIC FIELDS ON ONE ROW before any arithmetic reads it, the polling bound (iteration cap AND deadline), and
# the two verdicts (mutations unconditional, queue on stuck-ness). Only the table scope and the operator messages may
# legitimately differ, being specific to what each side is about to do. The driver rehearsal exercises BOTH copies, so a
# behavioural drift fails there rather than waiting for a reviewer.
assert_replication_settled() {
    local cluster deadline polls poll row
    local sample_sql queue_detail_sql mutation_detail_sql
    local queue age tries failures mutations mut_age mut_failed

    # Resolved here only to fail fast with a clear message and to name the cluster in what follows; the blocks reach it
    # through '{cluster}' themselves.
    cluster="$(ch "SELECT getMacro('cluster')")"
    [[ -n "$cluster" ]] || { echo "ERROR: could not resolve the '{cluster}' macro (getMacro('cluster') was empty). Pass --force only if you have confirmed replication settlement out of band." >&2; exit 1; }

    # All three rendered up front: a malformed marker then aborts before the first poll rather than midway through a
    # failure report, and the loop reuses one rendered string. Each token identifies exactly one block in 000003, so a
    # marker wrapped around the wrong statement is caught — clusterAllReplicas would match all three and prove nothing.
    sample_sql="$(render_settle settle-sample "CROSS JOIN")" || exit 2
    queue_detail_sql="$(render_settle settle-queue-detail postpone_reason)" || exit 2
    mutation_detail_sql="$(render_settle settle-mutation-detail latest_failed_part)" || exit 2

    # Bounded two ways on purpose: the loop header caps the iteration count, so the gate cannot spin whatever the clock
    # does, and the deadline check keeps a slow-reading cluster from overrunning the seconds --settle-timeout promises.
    # --settle-timeout 0 collapses this to a single sample. The last iteration always breaks (poll == polls fails the
    # guard), so the verdict below always runs against a sample that was actually read.
    polls=$(( SETTLE_TIMEOUT / SETTLE_POLL_SECONDS + 1 ))
    deadline=$(( SECONDS + SETTLE_TIMEOUT ))
    for (( poll = 1; poll <= polls; poll++ )); do
        row="$(ch "$sample_sql")" || row=""
        [[ -n "$row" ]] || { echo "ERROR: the settle gate could not read system.replication_queue / system.mutations across cluster '$cluster'. Grant SELECT ON system.* plus REMOTE and CLUSTER, or confirm settlement out of band and pass --force." >&2; exit 1; }
        # ONE row: `read` consumes only the first line, so a second row would be discarded in silence and the gate
        # would reach a verdict on a fragment. The settle-sample is a 1x1 CROSS JOIN of two single-row aggregates, so
        # more than one row means the markers moved onto a different statement, not that the cluster said more.
        [[ "$row" != *$'\n'* ]] || {
            echo "ERROR: the settle gate read MORE THAN ONE ROW from cluster '$cluster'. settle-sample returns exactly" >&2
            echo "       one row; extra rows mean the markers are around a different statement. Refusing to reach a" >&2
            echo "       verdict on the first line of it." >&2
            exit 1
        }
        read -r queue age tries failures mutations mut_age mut_failed <<<"$row"
        # A short or non-numeric row must not be read as a settled cluster. `read` leaves the unfilled variables EMPTY,
        # and bash arithmetic evaluates an empty string as 0 — so a header line, a truncated row or a changed block
        # would sail through every comparison below as "queue drained, nothing mutating" and pass the gate silently.
        # Seven numeric fields or nothing.
        for _field in "$queue" "$age" "$tries" "$failures" "$mutations" "$mut_age" "$mut_failed"; do
            [[ "$_field" =~ ^[0-9]+$ ]] || {
                echo "ERROR: the settle gate read '$row' from cluster '$cluster', which is not the seven numeric fields the" >&2
                echo "       settle-sample block returns. Refusing to reach a verdict on it — an unparsed field would be" >&2
                echo "       treated as 0 and pass the gate. Check the block's markers in $SETTLE_SQL, and that no client" >&2
                echo "       config overrides the output format." >&2
                exit 1
            }
        done

        if (( queue == 0 && mutations == 0 )); then
            echo "Replication settled across cluster '$cluster': queue drained, nothing still mutating '$PARKED_TABLE'."
            return 0
        fi
        (( poll < polls && SECONDS < deadline )) || break
        echo "  settling: replication_queue=$queue (oldest ${age}s, max num_tries=$tries, with last_exception=$failures)," \
             "unfinished mutations on '$PARKED_TABLE'=$mutations — polling up to ${SETTLE_TIMEOUT}s..."
        sleep "$SETTLE_POLL_SECONDS"
    done

    if (( mutations != 0 )); then
        echo "ERROR: a mutation on the parked '$PARKED_TABLE' has not finished on every replica after ${SETTLE_TIMEOUT}s" >&2
        echo "       ($mutations unfinished, oldest ${mut_age}s, $mut_failed carrying a latest_fail_reason). That table is frozen," >&2
        echo "       so this is a user delete that fired against it before the swap and is still applying. The sweep reads" >&2
        echo "       it mask-honored, so sweeping now would copy a deleted trace back into the live table." >&2
        echo "       Unfinished mutations:" >&2
        ch_vertical "$mutation_detail_sql" >&2 || true
        echo "       Let it finish (or fix the cause), then re-run. Raise --settle-timeout for a slow-but-progressing" >&2
        echo "       cluster; --force skips the gate entirely and the runbook's Go/No-Go forbids it in production." >&2
        exit 1
    fi

    if (( age > SETTLE_STUCK_AGE_SECONDS || tries > SETTLE_STUCK_NUM_TRIES || failures > 0 )); then
        echo "ERROR: a replica is lagging, not merely busy — after ${SETTLE_TIMEOUT}s the replication queue for" >&2
        echo "       $LIVE_TABLE / $PARKED_TABLE still holds $queue entries: oldest ${age}s (stuck above ${SETTLE_STUCK_AGE_SECONDS}s)," >&2
        echo "       max num_tries=$tries (stuck above ${SETTLE_STUCK_NUM_TRIES}), $failures carrying a last_exception." >&2
        echo "       The postcondition reads one replica, so a behind replica reads as missing keys and the sweep would" >&2
        echo "       re-copy rows that are already there. Oldest / most-retried entries:" >&2
        ch_vertical "$queue_detail_sql" >&2 || true
        echo "       Resolve the lag, then re-run. Raise --settle-timeout for a slow-but-progressing cluster; --force" >&2
        echo "       skips the gate entirely and the runbook's Go/No-Go forbids it in production." >&2
        exit 1
    fi

    # "Not stuck" rather than "moving": a snapshot predicate over the last sample read, with no path here comparing
    # consecutive samples. Polling only gives the queue time to drain (the early return above) or a stuck entry time to
    # age past the thresholds, so a shorter --settle-timeout is a weaker gate by exactly that much.
    echo "Replication settled enough across cluster '$cluster': nothing still mutating '$PARKED_TABLE', and no queue"
    echo "entry is stuck — $queue entries, oldest ${age}s, max num_tries=$tries, none with a last_exception."
    echo "That is ordinary ingest churn on a live table."
}

settle() {
    if [[ "$FORCE" == "1" ]]; then
        echo "WARNING: --force set; skipping the replication-settle gate. The postcondition below reads one replica." >&2
    else
        assert_replication_settled
    fi
}

# EVERY STATEMENT THIS DRIVER ISSUES IS SHARD-LOCAL, and so is the forward postcondition. The sweep reads a per-shard
# parked backup and writes the per-shard live table; the replay is a mutation, which travels by replication (a shard's
# replicas, not other shards); the forward postcondition joins two tables and so cannot use clusterAllReplicas. That
# makes a per-shard forward run correct — but it also means a single run's RECONCILED certifies ONE SHARD, and
# finalize.sh drops the parked backup ON CLUSTER, on every shard, on the strength of that assertion. So the scope has to
# be established before anything mutates, in BOTH directions, and an unreadable count fails closed exactly as
# rollback.sh's does:
#
#   * more than one shard, REVERSE — refused outright. The reverse replay this driver re-runs is shard-local while its
#     postcondition (000004_rollback_verify_replay.sql) reads clusterAllReplicas, so no single run can satisfy it. Worse
#     here than in rollback.sh, because that postcondition is advisory in this driver: the run would repair one shard,
#     warn, and still print RECONCILED off the shard-local four counts. rollback.sh refuses the same combination.
#   * more than one shard, FORWARD — allowed only with --confirm-single-shard, which is then an acknowledgment that this
#     run covers the connected shard alone and must be repeated on every shard before finalize.sh. Refusing outright
#     would leave a multi-shard estate with no driver path for a gap the drivers can actually close.
#   * count unreadable or 0 — the topology is UNKNOWN, so fail closed in both directions unless the operator asserts it
#     with --confirm-single-shard. Assuming the safe case is the run this check exists to stop. Zero is not one shard: an
#     empty system.macros match yields a default, so a missing 'cluster' macro returns 0 — and that also guarantees the
#     reverse postcondition's clusterAllReplicas('{cluster}', ...) cannot resolve.
assert_shard_scope() {
    local shards
    shards="$(ch "SELECT uniqExact(shard_num) FROM system.clusters
                  WHERE cluster = (SELECT substitution FROM system.macros WHERE macro = 'cluster')" 2>/dev/null || true)"

    if [[ "$shards" =~ ^[0-9]+$ ]] && (( shards > 1 )); then
        if [[ "$DIRECTION" == "reverse" ]]; then
            echo "ERROR: this cluster reports $shards shards. The reverse replay this driver re-runs after the sweep reaches" >&2
            echo "       only the shard you are connected to, while its postcondition reads every shard, so no single run can" >&2
            echo "       satisfy it. Apply the statements from $SQL_DIR by hand, one shard at a time, then check the" >&2
            echo "       postcondition once — the same path rollback.sh documents for its own reverse replay." >&2
            exit 1
        fi
        if [[ "$CONFIRM_SINGLE_SHARD" != "1" ]]; then
            echo "ERROR: this cluster reports $shards shards, and every statement this driver issues — plus its" >&2
            echo "       postcondition — covers only the shard you are connected to. A RECONCILED here would certify one" >&2
            echo "       shard while finalize.sh drops the parked backup ON CLUSTER, destroying the only copy of whatever" >&2
            echo "       is still unswept on the others." >&2
            echo "       Run this driver once PER SHARD with --confirm-single-shard, and do not run finalize.sh until every" >&2
            echo "       shard has reported RECONCILED." >&2
            exit 1
        fi
        SHARD_SCOPE_NOTE="this run covered ONE of $shards shards"
        echo "NOTE: $shards shards; proceeding on --confirm-single-shard. This run reconciles and certifies only the shard" >&2
        echo "      reached through ${CH_HOST:-the default host}. Repeat it on every shard before finalize.sh." >&2
        return 0
    fi

    if ! [[ "$shards" =~ ^[0-9]+$ ]] || (( shards == 0 )); then
        if [[ "$CONFIRM_SINGLE_SHARD" == "1" ]]; then
            echo "NOTE: could not read the shard count; proceeding on --confirm-single-shard. If the cluster in fact has" >&2
            echo "      more than one shard, this reconciles and certifies only the shard you are connected to." >&2
            SHARD_SCOPE_NOTE="shard count unread; scope asserted by --confirm-single-shard"
            return 0
        fi
        echo "ERROR: the shard count came back unusable ('${shards:-<empty>}'), so this cluster's topology is unknown. It" >&2
        echo "       needs SELECT on system.clusters and system.macros, and a 0 means the 'cluster' macro did not resolve." >&2
        echo "       Every statement here is shard-local, so on more than one shard this would reconcile one shard and" >&2
        echo "       report RECONCILED for the estate — which finalize.sh then acts on by dropping the backup ON CLUSTER." >&2
        echo "       Grant the reads, or pass --confirm-single-shard to assert the topology yourself." >&2
        exit 1
    fi
}

# Extract one `-- >>> BEGIN <name>` .. `-- >>> END <name>` block (exact-line markers) from a reference file.
extract() {
    awk -v begin="-- >>> BEGIN $2" -v end="-- >>> END $2" '$0 == begin {f = 1; next} $0 == end {f = 0} f' "$1"
}

# Refuse rendered SQL that is not what the caller asked for. A marker renamed, moved, indented or split yields text that
# is empty or only the block's own comments, and clickhouse-client exits 0 on either, so `set -e` never fires and the
# step prints its success line having done nothing. Same guard, same reasoning, as exchange_and_wrap.sh's.
require_rendered() {
    local sql="$1" what="$2" must_contain="$3" file="$4" masked begins ends begin_line end_line
    # Exactly one pair, checked on the FILE rather than the extraction: the awk stops at the first END and otherwise runs
    # to EOF, so a missing or renamed END sweeps every later block into this one. The content checks cannot see that.
    begins="$(grep -cxF -e "-- >>> BEGIN $what" "$file" || true)"
    ends="$(grep -cxF -e "-- >>> END $what" "$file" || true)"
    if (( begins != 1 || ends != 1 )); then
        echo "ERROR: $file holds $begins '-- >>> BEGIN $what' and $ends '-- >>> END $what'; expected one of each." >&2
        exit 2
    fi
    # Second: the END must sit AFTER its BEGIN, the same check exchange_and_wrap.sh's copy makes. Counting alone misses
    # a reordered END, which still counts 1 and 1: extract's awk clears its flag only on a line equal to END, so with
    # the END behind the BEGIN it captures to end of file and sweeps every later block in. That run-on is non-empty,
    # mentions the identity token and holds no placeholder, so all the checks below pass on several statements — and
    # the multi-result-set output it produces is what read_postcondition would otherwise read a verdict from.
    read -r begin_line end_line <<<"$(awk -v b="-- >>> BEGIN $what" -v e="-- >>> END $what" \
        '$0 == b {bl = NR} $0 == e {el = NR} END {print bl, el}' "$file")"
    if (( end_line <= begin_line )); then
        echo "ERROR: $file has the '$what' markers out of order (BEGIN at line $begin_line, END at line $end_line)," >&2
        echo "       so the block would capture to end of file and sweep every later block into it. Refusing to run it." >&2
        exit 2
    fi
    masked="$(sed 's/--.*$//' <<<"$sql")"
    if [[ -z "${masked//[[:space:]]/}" ]]; then
        echo "ERROR: the '$what' block from $file rendered no executable SQL (empty, or comments only)." >&2
        echo "       Expected the exact marker lines '-- >>> BEGIN $what' and '-- >>> END $what'." >&2
        exit 2
    fi
    if ! grep -qF "$must_contain" <<<"$masked"; then
        echo "ERROR: the '$what' block from $file has no '$must_contain' outside its comments, so the markers are" >&2
        echo "       around the wrong statement. Refusing to run it." >&2
        exit 2
    fi
    if grep -qF '${' <<<"$masked"; then
        echo "ERROR: the '$what' block from $file still holds an unsubstituted \${...} placeholder after rendering." >&2
        echo "       Refusing to send SQL containing a literal placeholder." >&2
        exit 2
    fi
}

# One of 000003's shared settle-* blocks, rendered with this driver's POST-SWAP scope: the queue side covers both tables
# the postcondition joins, the mutation side only the frozen parked one. Kept separate from render() because it
# substitutes a different placeholder set — the sweep's anchors mean nothing here, and the settle scope means nothing
# there — so neither function can silently leave the other's placeholder in place.
#
# require_rendered's `exit` runs in this function's own command substitution at the call sites, hence their `|| exit 2`.
render_settle() {
    local sql queue_tables mutation_tables
    # Built as variables, not inlined: the replacement half of ${x//pat/repl} does not honor nested quoting, so a
    # literal "'$A', '$B'" there would substitute the double quotes into the SQL as well.
    queue_tables="'$LIVE_TABLE', '$PARKED_TABLE'"
    mutation_tables="'$PARKED_TABLE'"
    sql="$(extract "$SETTLE_SQL" "$1")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${SETTLE_QUEUE_TABLES}'/$queue_tables}"
    sql="${sql//'${SETTLE_MUTATION_TABLES}'/$mutation_tables}"
    require_rendered "$sql" "$1" "$2" "$SETTLE_SQL"
    printf '%s' "$sql"
}

# Substitutions shared by every block this driver renders.
render() {
    local sql="$1"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${LIVE_TABLE}'/$LIVE_TABLE}"
    sql="${sql//'${GAP_START}'/$EFFECTIVE_GAP_START}"
    sql="${sql//'${SWAP_DONE}'/$SWAP_DONE}"
    sql="${sql//'${MAX_PARTITIONS_PER_INSERT_BLOCK}'/$MAX_PARTITIONS_PER_INSERT_BLOCK}"
    printf '%s' "$sql"
}

# One marked block of 000006. Called BARE, never in a `||` list, so `set -e` aborts the run if the statement fails —
# which is right: a sweep that did not land its rows, or a replay that did not apply, must not be followed by a verdict.
run_block() {
    local block="$1" sql
    sql="$(render "$(extract "$SWEEP_SQL" "$block")")"
    require_rendered "$sql" "$block" "$PARKED_TABLE" "$SWEEP_SQL"   # re-checked per pass: cheap, and catches a mid-run edit
    # --time prints the statement's elapsed seconds to stderr (a bare --query prints nothing). These wall times are what
    # size the reconciliation step in the runbook's timings, so they are recorded rather than guessed.
    clickhouse-client "${CH_ARGS[@]}" --time --multiquery --query "$sql"
}

# The reverse replay, unchanged from the rollback path: 000004_rollback_reverse_replay.sql re-applies every delete
# bridged since cutover_start onto the restored original (deliberately guard-less — see its header). Run AFTER the sweep,
# which is what makes deletes win over the re-imported writes.
#
# Called BARE, never in a `||` list: a failing statement here means post-cutover deletes were not re-applied, so `set -e`
# aborting the run is the correct outcome. (Inside a condition context bash suppresses `set -e` for a function's whole
# body, which would let a failed DELETE fall through to the check below and be reported as a clean replay.)
run_reverse_deletion_replay() {
    local sql
    sql="$(cat "$REVERSE_REPLAY_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    clickhouse-client "${CH_ARGS[@]}" --time --multiquery --query "$sql"
}

# 000004_rollback_verify_replay.sql, unchanged: asserts no id bridged since cutover_start is live again. Advisory here,
# unlike in rollback.sh — the reconciliation postcondition is the gate this driver exits on, and it would report the same
# key as a divergence — so this is the one call that belongs in a condition context. It names which check spoke, so a
# resurrected key is not confused with a sweep that failed to land rows.
verify_reverse_replay() {
    local sql resurrected
    sql="$(cat "$REVERSE_VERIFY_SQL")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    resurrected="$(clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --query "$sql")"
    if [[ "$resurrected" == "0" ]]; then
        echo "  Reverse-replay postcondition OK: no id bridged since cutover_start is live on the restored '$LIVE_TABLE'."
        return 0
    fi
    if ! [[ "$resurrected" =~ ^[0-9]+$ ]]; then
        echo "  WARNING: reverse-replay postcondition COULD NOT BE EVALUATED — the query returned no usable count." >&2
    else
        echo "  WARNING: reverse-replay postcondition FAILED — $resurrected id(s) deleted after cutover_start are live" >&2
        echo "           again on '$LIVE_TABLE'. The sweep re-imported them and the replay did not mask them." >&2
    fi
    return 1
}

# Render and validate every block this run will use, ONCE, at top level. It is not merely an early check: the counts are
# read inside a command substitution, where an exit from require_rendered ends only the subshell — so a renamed marker or
# a surviving placeholder would reach the caller as "the counts could not be read", pointing at connectivity instead of
# at the file. rollback.sh validates its sentinel SQL up front for exactly this reason. Only the blocks the chosen
# direction and mode actually run are checked, so an unrelated block's breakage does not block a --report-only.
validate_blocks() {
    local block
    validate_block "$VERIFY_SQL" "verify-$DIRECTION"
    [[ "$DIRECTION" != "forward" ]] || validate_block "$VERIFY_SQL" leak-check-forward
    [[ "$REPORT_ONLY" == "1" ]] && return 0
    for block in "${MUTATING_BLOCKS[@]}"; do
        validate_block "$SWEEP_SQL" "$block"
    done
    return 0
}

validate_block() {
    require_rendered "$(render "$(extract "$1" "$2")")" "$2" "$PARKED_TABLE" "$1"
}

# The four counts, as one tab-separated line, so the driver gates on them instead of leaving numbers on a screen.
# Called inside a command substitution, so it deliberately does NO validation of its own — see validate_blocks.
reconciliation_counts() {
    clickhouse-client "${CH_ARGS[@]}" --format TabSeparated --multiquery \
        --query "$(render "$(extract "$VERIFY_SQL" "verify-$DIRECTION")")"
}

MISSING=""; STALE=""; PAYLOAD=""; NEWER=""
# Reads the postcondition into the four globals. Returns non-zero when the output is not four counts (a dead client, a
# refused read) — which is "not verified" rather than "clean", and must never be allowed to read as a pass.
read_postcondition() {
    local out
    if ! out="$(reconciliation_counts)"; then out=""; fi
    # ONE row, for the same reason the settle gate insists on it: `read` takes only the first line, so a second result
    # set is discarded in silence and the verdict is reached on a fragment — and every field on line 1 can be a
    # legitimate number, so the regex below cannot catch it. This is the read finalize.sh's --confirm-gap-reconciled
    # rests on, and a false clean here ends with the parked backup dropped ON CLUSTER. verify-forward / verify-reverse
    # are single global aggregates with no GROUP BY, so more than one row means the markers are around a different
    # statement — the state require_rendered's out-of-order check catches at the file level.
    if [[ "$out" == *$'\n'* ]]; then
        echo "ERROR: the reconciliation postcondition read MORE THAN ONE ROW. verify-$DIRECTION returns exactly one;" >&2
        echo "       extra rows mean the markers are around a different statement in $VERIFY_SQL." >&2
        return 1
    fi
    read -r MISSING STALE PAYLOAD NEWER <<< "$out"
    [[ "$MISSING" =~ ^[0-9]+$ && "$STALE" =~ ^[0-9]+$ && "$PAYLOAD" =~ ^[0-9]+$ && "$NEWER" =~ ^[0-9]+$ ]]
}

print_counts() {
    echo "  missing_keys=$MISSING stale_keys=$STALE payload_mismatch_keys=$PAYLOAD (gate: all three at 0)"
    echo "  newer_keys=$NEWER  — informational, and EXPECTED to be non-zero: a gap-window trace written again after the"
    echo "                        swap is newer on the live side, which the sweep deliberately leaves alone."
}

# 000006_verify_reconciliation.sql's leak-check-forward: bridged deletes still LIVE on the successor at a version the
# frozen backup itself held. Forward only, and NOT part of the gate — it reports the residual arm 3 of the deletion
# replay cannot prevent (a pre-swap row carrying a client-supplied FUTURE last_updated_at falls outside its staleness
# scope; see that block's header). Advisory, and non-fatal on a read failure: the four counts are what this driver exits
# on, and a failed advisory read must not turn a clean reconciliation into a failure. Printed AFTER the gate so a
# non-zero here is read as "reconciled, and these deletes need re-applying by hand", which is what it means.
print_leak_check() {
    local leaked
    [[ "$DIRECTION" == "forward" ]] || return 0
    leaked="$(clickhouse-client "${CH_ARGS[@]}" --format TabSeparated \
        --query "$(render "$(extract "$VERIFY_SQL" leak-check-forward)")" 2>/dev/null || true)"
    if [[ "$leaked" == "0" ]]; then
        echo "  leaked_delete_keys=0 — no captured delete is live on '$LIVE_TABLE' at a version the backup held."
        return 0
    fi
    if ! [[ "$leaked" =~ ^[0-9]+$ ]]; then
        echo "  WARNING: the deletion-leak advisory could not be read; the four counts above still stand." >&2
        return 0
    fi
    echo "  WARNING: leaked_delete_keys=$leaked — that many CAPTURED DELETES are still live on '$LIVE_TABLE'." >&2
    echo "           The reconciliation itself is complete; this is the residual the replay's staleness scope cannot" >&2
    echo "           prevent, because last_updated_at is client-supplied (see 000006's ARM 3 header). Those keys are" >&2
    echo "           still in deletion_events_local, so they can be re-applied by hand — list them with the" >&2
    echo "           leak-check-forward block in $VERIFY_SQL, dropping its outer count()." >&2
    return 0
}

gate_is_clean() {
    (( MISSING == 0 && STALE == 0 && PAYLOAD == 0 ))
}

resolve_live_table
detect_direction

# Direction-dependent argument rules, asserted now that the topology has named the direction. Each rejected flag asserts
# a precondition for something the other direction does, so accepting one would confirm a wrong mental model.
if [[ "$DIRECTION" == "forward" ]]; then
    [[ -n "$GAP_START" ]] || {
        echo "ERROR: the forward direction requires --gap-start: the start of the last delta pass, which delta_replay.sh" >&2
        echo "       prints as 'RECORD delta_start='. It bounds what the sweep copies. Widening it is free — the sweep is" >&2
        echo "       mask-honored and idempotent — so pass backfill_start if delta_start was not recorded." >&2
        exit 2
    }
    [[ -z "$CUTOVER_START" ]] || {
        echo "ERROR: --cutover-start belongs to the reverse direction, which replays deletes from it. The forward" >&2
        echo "       reconciliation bounds its copy with --gap-start and its exclusion with --swap-done; neither is" >&2
        echo "       cutover_start, and using it as either would be wrong (see the option docs)." >&2
        exit 2
    }
    [[ "$CONFIRM_REIMPORT" != "1" ]] || {
        echo "ERROR: --confirm-reimport-successor-writes belongs to the reverse direction. Forward, the sweep restores" >&2
        echo "       writes the cutover LOST rather than writes a rollback chose to discard, so there is nothing to" >&2
        echo "       acknowledge: it is repair, not a reversal of a decision." >&2
        exit 2
    }
else
    [[ -n "$CUTOVER_START" ]] || {
        echo "ERROR: the reverse direction requires --cutover-start (the value exchange_and_wrap.sh printed). It bounds" >&2
        echo "       the reverse deletion replay this driver re-runs after the sweep, exactly as it does in rollback.sh." >&2
        echo "       Estimating it loses data in either direction — if it was lost, stop and escalate (see the runbook)." >&2
        exit 2
    }
    # cutover_start is the reverse gap anchor by definition; --gap-start only ever widens it.
    [[ -n "$GAP_START" ]] || GAP_START="$CUTOVER_START"
    if [[ "$REPORT_ONLY" != "1" && "$CONFIRM_REIMPORT" != "1" ]]; then
        echo "ERROR: the reverse direction requires --confirm-reimport-successor-writes. The sweep re-imports exactly the" >&2
        echo "       post-cutover writes --accept-post-cutover-write-loss acknowledged discarding, so this asserts you now" >&2
        echo "       want them back. That is right when the rollback was motivated by latency, merge load or the wrap; it" >&2
        echo "       is WRONG when the successor's content is what is suspect, since it re-imports the very data the" >&2
        echo "       rollback existed to discard. Use --report-only to size the gap without re-importing anything." >&2
        exit 2
    fi
    [[ "$LIVE_TABLE" == "traces" ]] || {
        echo "ERROR: the reverse direction expects the restored original live under 'traces', but this estate resolves its" >&2
        echo "       live table to '$LIVE_TABLE' (i.e. 'traces' is a Distributed wrapper). A rollback removes the wrapper," >&2
        echo "       so this state is not one a promote produces. Resolve by hand." >&2
        exit 1
    }
fi

# Retention is disabled in every deployment and the runbook requires it stay paused for the window — but the window it
# names ends at the EXCHANGE, and this driver runs after it, which is exactly where a retention delete would be undone.
# Asserted here for the same reason exchange_and_wrap.sh and rollback.sh assert it, and skipped for --report-only, which
# issues no statement.
if [[ "$REPORT_ONLY" != "1" && "$CONFIRM_RETENTION_PAUSED" != "1" ]]; then
    echo "ERROR: reconciliation requires --confirm-retention-paused. Retention deletes bypass the deletion bridge, so" >&2
    echo "       this driver cannot see them: one that fired after '$PARKED_TABLE' froze leaves its trace masked on" >&2
    echo "       '$LIVE_TABLE', still live in the frozen backup and absent from the bridge — the sweep re-inserts it and" >&2
    echo "       the replay, which only re-applies bridged keys, leaves it live. The delete is undone." >&2
    echo "       Confirm RETENTION_ENABLED=false on every backend for the whole window INCLUDING this step, then re-run." >&2
    echo "       Use --report-only to read the counts without issuing any statement; it does not need this flag." >&2
    exit 2
fi

assert_shard_scope

# Widen the gap anchor downward by --slack-seconds, server-side: no host date math and no timezone ambiguity, the same
# reason every other window bound in this runbook is computed in ClickHouse.
EFFECTIVE_GAP_START="$(ch "SELECT toString(subtractSeconds(toDateTime64('$GAP_START', 6, 'UTC'), $SLACK_SECONDS))")"
[[ "$EFFECTIVE_GAP_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || {
    echo "ERROR: could not compute the slack-widened gap anchor from '$GAP_START' - ${SLACK_SECONDS}s (got '$EFFECTIVE_GAP_START')." >&2
    exit 1
}

validate_blocks

echo "Direction: $DIRECTION  (parked '$PARKED_TABLE' -> live '$LIVE_TABLE')"
echo "Gap window from $EFFECTIVE_GAP_START UTC (= $GAP_START minus ${SLACK_SECONDS}s slack); swap_done $SWAP_DONE UTC"

settle
read_postcondition || {
    echo "ERROR: the reconciliation postcondition could not be read, so it is unknown whether there is a gap." >&2
    echo "       Treat this as unverified, not as clean. Fix connectivity and re-run — no mutation was issued." >&2
    exit 1
}
echo "Postcondition BEFORE any mutation:"
print_counts
print_leak_check

if gate_is_clean; then
    echo "Nothing to reconcile: every key live in '$PARKED_TABLE' inside the gap window is present on '$LIVE_TABLE' at the"
    echo "same version or newer. No statement issued."
    # Same qualifier as the RECONCILED verdict below, and for the same reason: this is a success outcome an operator
    # acts on with finalize.sh, whose DROP is ON CLUSTER. A shard-local "nothing to do" must not read as an estate-wide
    # one either. (The --report-only exit below needs no qualifier: it exits non-zero saying the estate is NOT
    # reconciled, and assert_shard_scope has already named the scope on stderr.)
    [[ -z "$SHARD_SCOPE_NOTE" ]] || {
        echo "SCOPE: $SHARD_SCOPE_NOTE. This covers that shard ONLY — every other shard needs its own clean gate"
        echo "       before finalize.sh, which drops the parked backup on all of them."
    }
    exit 0
fi

if [[ "$REPORT_ONLY" == "1" ]]; then
    echo "--report-only: no mutation issued. The estate is NOT reconciled — re-run without --report-only to sweep it." >&2
    exit 1
fi

PASS=0
while (( PASS < MAX_PASSES )); do
    PASS=$(( PASS + 1 ))
    echo "Pass $PASS/$MAX_PASSES:"
    for block in "${MUTATING_BLOCKS[@]}"; do
        run_block "$block"
    done
    if [[ "$DIRECTION" == "reverse" ]]; then
        run_reverse_deletion_replay
        verify_reverse_replay || true
    fi
    settle
    read_postcondition || {
        echo "ERROR: the reconciliation postcondition could not be read after pass $PASS. The sweep ran, so treat the" >&2
        echo "       estate as UNVERIFIED rather than reconciled: fix connectivity and re-run (this driver is idempotent)." >&2
        exit 1
    }
    print_counts
    if gate_is_clean; then
        echo
        echo "RECONCILED after $PASS pass(es): missing_keys=0 stale_keys=0 payload_mismatch_keys=0."
        print_leak_check
        # A shard-local verdict must never read as an estate-wide one: finalize.sh destroys the parked backup ON CLUSTER
        # on the strength of this line, so when the scope was asserted rather than proven, the line says so.
        [[ -z "$SHARD_SCOPE_NOTE" ]] || {
            echo "SCOPE: $SHARD_SCOPE_NOTE. This verdict covers that shard ONLY — every other shard needs its own"
            echo "       RECONCILED before finalize.sh, which drops the parked backup on all of them."
        }
        if [[ "$DIRECTION" == "forward" ]]; then
            echo "Every trace written to the old table in the [$EFFECTIVE_GAP_START, swap) gap is live on '$LIVE_TABLE'."
            echo
            echo "NEXT:"
            echo "  1. Payload-level picture over exactly the range that was reconciled, alongside the usual weekly compare:"
            echo "       ./verify.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} \\"
            echo "           --old-table traces_pre_cutover_backup --new-table $LIVE_TABLE \\"
            echo "           --window-from '$EFFECTIVE_GAP_START' --window-to '<now, UTC>'"
            echo "  2. Keep '$PARKED_TABLE' for the soak. finalize.sh refuses to retire it without --confirm-gap-reconciled,"
            echo "     which is this run."
        else
            echo "The post-cutover writes the rollback discarded are live again on '$LIVE_TABLE', with sentinels"
            echo "denormalized back to NULL, and every delete bridged since cutover_start is still masked."
            echo
            echo "NEXT: the post-rollback fidelity compare no longer needs the --to-week exclusion for WRITES — this run"
            echo "      removed that divergence. Residual differences are post-cutover deletes and post-promote writes."
        fi
        exit 0
    fi
    echo "  Gate still non-zero after pass $PASS; retrying (concurrent traffic can add to the gap while the sweep runs)."
done

echo >&2
echo "RECONCILIATION FAILED: the gate is still non-zero after $MAX_PASSES pass(es) —" >&2
echo "  missing_keys=$MISSING stale_keys=$STALE payload_mismatch_keys=$PAYLOAD" >&2
echo "This is NOT convergence stalling on write volume: the parked table is frozen, so repeated passes cannot keep" >&2
echo "finding new work unless something else is wrong. Investigate before re-running:" >&2
echo "  * missing_keys — the sweep did not land those rows. Check the run's errors, and check that --swap-done is not" >&2
echo "    LATER than the actual swap (a late value under-excludes; an early one over-excludes and shows up exactly here)." >&2
echo "  * stale_keys / payload_mismatch_keys — the live row disagrees with the frozen one at the same or an older" >&2
echo "    version, which a sweep cannot fix by re-inserting. Triage with:" >&2
# verify.sh's --old-table is always the OLD-SCHEMA side (Nullable, nanosecond), which is the parked backup forward and
# the live original in reverse — the two directions put opposite tables there, so the pair is resolved rather than fixed.
if [[ "$DIRECTION" == "forward" ]]; then
    verify_pair="--old-table $PARKED_TABLE --new-table $LIVE_TABLE"
else
    verify_pair="--old-table $LIVE_TABLE --new-table $PARKED_TABLE"
fi
echo "      ./verify.sh --database $DATABASE ${CH_HOST:+--host $CH_HOST} ${CH_PORT:+--port $CH_PORT} \\" >&2
echo "          $verify_pair \\" >&2
echo "          --window-from '$EFFECTIVE_GAP_START' --window-to '<now, UTC>' --drill-down" >&2
echo "Do NOT run finalize.sh: the parked backup is the only copy of whatever is still missing." >&2
exit 1
