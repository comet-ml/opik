#!/usr/bin/env bash
#
# Driver for rolling the buffered traces cutover back (runbook: ../README.md).
#
# Runs the db-app-analytics/000004_rollback_* file(s) that match how far the cutover got. Pick the stage by the last
# step that completed:
#   --stage A   backfill/delta ran but the EXCHANGE did not — discard the shadow (live `traces` is untouched).
#   --stage B   the EXCHANGE ran but not the wrap — swap the tables back, then reverse-replay.
#   --stage C   the wrap ran — drop the wrapper, promote the parked original, then reverse-replay.
# Stages B and C need --cutover-start (printed by exchange_and_wrap.sh) to bound the reverse-replay. Keep the deletion
# bridge enabled through the rollback so no delete is lost.
#
# SAFETY: the stages are mutually exclusive and each lives in its OWN file, so no single file mixes a TRUNCATE with an
# EXCHANGE/DROP — running any file does exactly one stage. Before running, this asserts the live `traces` topology matches
# the requested stage and aborts otherwise, so a wrong-stage run cannot destroy data. No data-bearing table is dropped;
# every stage ends in the canonical state (traces = original data live, traces_local_v2 = successor data parked). The
# parked backup is dropped only later by finalize.sh, after the soak.
#
# Connection: clickhouse-client env vars (CLICKHOUSE_HOST, CLICKHOUSE_PORT, CLICKHOUSE_USER, CLICKHOUSE_PASSWORD).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$SCRIPT_DIR/db-app-analytics"

DATABASE=""
STAGE=""
CUTOVER_START=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --database) DATABASE="$2"; shift 2 ;;
        --stage) STAGE="$2"; shift 2 ;;
        --cutover-start) CUTOVER_START="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$DATABASE" ]] || { echo "ERROR: --database is required" >&2; exit 2; }
# --database and --cutover-start are interpolated into the reference SQL; validate their shapes so neither can alter it.
[[ "$DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || { echo "ERROR: --database must be a ClickHouse identifier (letters, digits, underscore)." >&2; exit 2; }
[[ -z "$CUTOVER_START" || "$CUTOVER_START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$ ]] || { echo "ERROR: --cutover-start must be 'YYYY-MM-DD HH:MM:SS[.ffffff]'." >&2; exit 2; }
case "$STAGE" in
    A|B|C) ;;
    *) echo "ERROR: --stage must be A, B or C" >&2; exit 2 ;;
esac

ch() {
    clickhouse-client --database "$DATABASE" --query "$1"
}

# Single scalar (or empty string if the object does not exist). Used by the topology guards below.
traces_engine() {
    ch "SELECT engine FROM system.tables WHERE database = '$DATABASE' AND name = '$1'"
}
traces_endtime_type() {
    ch "SELECT type FROM system.columns WHERE database = '$DATABASE' AND table = '$1' AND name = 'end_time'"
}

# The migration walks traces through three shapes; a stage is only valid in one of them:
#   pre-EXCHANGE       -> traces is a *MergeTree with Nullable end_time (the original schema)      -> stage A
#   post-EXCHANGE      -> traces is a *MergeTree with non-Nullable end_time (the successor schema) -> stage B
#   post-wrap          -> traces is a Distributed table                                           -> stage C
# Asserting the shape makes a wrong-stage run (which is where a TRUNCATE/DROP would be catastrophic) abort with no change.
assert_topology() {
    local engine end_time
    engine="$(traces_engine traces)"
    end_time="$(traces_endtime_type traces)"
    [[ -n "$engine" ]] || { echo "ERROR: no 'traces' table found in database '$DATABASE'." >&2; exit 1; }

    case "$STAGE" in
        A)
            [[ "$engine" != "Distributed" && "$end_time" == Nullable* ]] || {
                echo "ERROR: stage A expects the pre-EXCHANGE state (traces = original schema), but traces is engine='$engine' end_time='$end_time'." >&2
                echo "       The EXCHANGE has already run — truncating the shadow now would destroy the parked original. Use stage B or C." >&2
                exit 1
            }
            [[ -n "$(traces_engine traces_local_v2)" ]] || { echo "ERROR: shadow table 'traces_local_v2' not found; nothing to discard." >&2; exit 1; }
            ;;
        B)
            [[ "$engine" != "Distributed" ]] || {
                echo "ERROR: stage B expects the post-EXCHANGE, pre-wrap state, but traces is Distributed (the wrap ran). Use stage C." >&2
                exit 1
            }
            [[ "$end_time" != Nullable* ]] || {
                echo "ERROR: stage B expects traces to hold the successor schema, but end_time is Nullable (the EXCHANGE has not run). Nothing to roll back; use stage A to discard the shadow." >&2
                exit 1
            }
            [[ -n "$(traces_engine traces_pre_cutover_backup)" ]] || { echo "ERROR: 'traces_pre_cutover_backup' (parked original) not found; cannot swap back." >&2; exit 1; }
            ;;
        C)
            [[ "$engine" == "Distributed" ]] || {
                echo "ERROR: stage C expects the post-wrap state (traces = Distributed), but traces is engine='$engine'. The wrap was not applied — use stage B." >&2
                exit 1
            }
            [[ -n "$(traces_engine traces_local)" ]] || { echo "ERROR: 'traces_local' (successor data) not found; topology is not a clean post-wrap state." >&2; exit 1; }
            [[ -n "$(traces_engine traces_pre_cutover_backup)" ]] || { echo "ERROR: 'traces_pre_cutover_backup' (parked original) not found; topology is not a clean post-wrap state." >&2; exit 1; }
            ;;
    esac
}

# Run one rollback .sql file wholesale, substituting the placeholders. Each file is exactly one stage's statements.
run_file() {
    local file="$SQL_DIR/$1" sql
    [[ -f "$file" ]] || { echo "ERROR: cannot find $file" >&2; exit 2; }
    sql="$(cat "$file")"
    sql="${sql//'${ANALYTICS_DB_DATABASE_NAME}'/$DATABASE}"
    sql="${sql//'${CUTOVER_START}'/$CUTOVER_START}"
    clickhouse-client --database "$DATABASE" --multiquery --query "$sql"
}

assert_topology

case "$STAGE" in
    A)
        run_file 000004_rollback_stage_a_discard_shadow.sql
        echo "Stage A done: shadow discarded. Live 'traces' was untouched."
        ;;
    B)
        [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for stage B" >&2; exit 2; }
        run_file 000004_rollback_stage_b_exchange_back.sql
        run_file 000004_rollback_reverse_replay.sql
        echo "Stage B done: tables swapped back and deletes since cutover_start re-applied."
        ;;
    C)
        [[ -n "$CUTOVER_START" ]] || { echo "ERROR: --cutover-start is required for stage C" >&2; exit 2; }
        run_file 000004_rollback_stage_c_promote_original.sql
        run_file 000004_rollback_reverse_replay.sql
        echo "Stage C done: wrapper dropped, original promoted, deletes since cutover_start re-applied."
        ;;
esac

echo "Now in the canonical state: traces = original data (live), traces_local_v2 = successor data (parked)."
echo "Verify (README 'Verifying the migration'), then drop the parked backup with finalize.sh once healthy."
