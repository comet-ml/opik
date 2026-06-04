#!/usr/bin/env bash
#
# load-trace-threads.sh — Reproduce OPIK-6749 locally.
#
# Seeds opik.trace_threads with a "hot" workspace (lots of stale active rows)
# plus a handful of normal workspaces, then runs the exact query from
# TraceThreadDAO.FIND_PENDING_CLOSURE_THREADS_SQL in two forms:
#   (a) global — as the job runs it today
#   (b) per-workspace prefix-filtered on the hot workspace_id
# and pulls timing/rows-read/memory from system.query_log for both.
#
# Usage:
#   scripts/load-trace-threads.sh seed      # insert synthetic data
#   scripts/load-trace-threads.sh probe     # run global + per-ws queries, print comparison
#   scripts/load-trace-threads.sh explain   # EXPLAIN indexes=1 + EXPLAIN PIPELINE for both
#   scripts/load-trace-threads.sh cleanup   # delete only the synthetic rows
#   scripts/load-trace-threads.sh all       # seed + probe + explain
#
# Env overrides:
#   CH_URL=http://localhost:8123  CH_USER=opik  CH_PASS=opik  CH_DB=opik
#   HOT_ROWS=5000000        rows for the hot workspace
#   NORMAL_WORKSPACES=20    number of small workspaces
#   NORMAL_ROWS_PER_WS=10000
#   LIMIT=1000              :limit bound passed to the query
#   DEFAULT_TIMEOUT_SECONDS=300

set -euo pipefail

CH_URL="${CH_URL:-http://localhost:8123}"
CH_USER="${CH_USER:-opik}"
CH_PASS="${CH_PASS:-opik}"
CH_DB="${CH_DB:-opik}"

HOT_THREADS="${HOT_THREADS:-${HOT_ROWS:-5000000}}"      # unique threads in the hot workspace
NORMAL_WORKSPACES="${NORMAL_WORKSPACES:-20}"
NORMAL_THREADS_PER_WS="${NORMAL_THREADS_PER_WS:-${NORMAL_ROWS_PER_WS:-10000}}"
UPDATES_PER_THREAD="${UPDATES_PER_THREAD:-1}"           # how many ReplacingMergeTree versions per thread
UPDATE_DELTA_MIN="${UPDATE_DELTA_MIN:-5}"               # minutes between versions of the same thread
LIMIT="${LIMIT:-1000}"
DEFAULT_TIMEOUT_SECONDS="${DEFAULT_TIMEOUT_SECONDS:-300}"

HOT_WS="d9c80134-0000-0000-0000-000000000000"
NORMAL_WS_PREFIX="d9c80134-1111-1111-1111-"   # padded with right-zero to 36 chars
PROJECT_HOT="11111111-1111-1111-1111-111111111111"
SEED_TAG="opik6749_load_test"                 # used in log_comment and in `created_by`

# ---- helpers --------------------------------------------------------------

ch() {
    # Run a query, return raw body. Honor ?database=... so we don't need USE.
    curl -sS --fail-with-body \
        "${CH_URL}/?database=${CH_DB}&user=${CH_USER}&password=${CH_PASS}" \
        --data-binary "$1"
}

probe_query_sql() {
    # The query verbatim from TraceThreadDAO.FIND_PENDING_CLOSURE_THREADS_SQL,
    # with :now, :cached_max_inactive_period, :default_timeout_seconds, :limit
    # already substituted, and an optional extra predicate injected into the
    # inner SELECT for the per-workspace variant.
    local extra_predicate="$1"
    local log_comment="$2"

    cat <<SQL
SELECT
    tt.workspace_id,
    tt.project_id
FROM (
    SELECT workspace_id, project_id, thread_id, id, status, last_updated_at
    FROM trace_threads
    WHERE last_updated_at > now64(6) - INTERVAL 1 DAY
    ${extra_predicate}
    ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
    LIMIT 1 BY (workspace_id, project_id, thread_id, id)
) tt
LEFT ANY JOIN workspace_configurations wc FINAL
    ON tt.workspace_id = wc.workspace_id
WHERE tt.status = 'active'
  AND tt.last_updated_at < now64(6) - INTERVAL IF(wc.timeout_mark_thread_as_inactive > 0,
                                                   wc.timeout_mark_thread_as_inactive,
                                                   ${DEFAULT_TIMEOUT_SECONDS}) SECOND
GROUP BY tt.workspace_id, tt.project_id
ORDER BY min(tt.last_updated_at)
LIMIT ${LIMIT}
SETTINGS log_comment = '${log_comment}'
FORMAT Null
SQL
}

# Alternative: replace `ORDER BY DESC + LIMIT 1 BY` dedup with GROUP BY + argMax.
# Same lookback bound, same outer per-workspace timeout via workspace_configurations join.
# Aggregation is typically better than per-row sort in ClickHouse — this is the
# hypothesis we're testing.
probe_query_argmax_sql() {
    local extra_predicate="$1"
    local log_comment="$2"

    cat <<SQL
SELECT
    tt.workspace_id,
    tt.project_id
FROM (
    SELECT
        workspace_id, project_id, thread_id, id,
        argMax(status, last_updated_at)  AS latest_status,
        max(last_updated_at)             AS latest_updated_at
    FROM trace_threads
    WHERE last_updated_at > now64(6) - INTERVAL 1 DAY
    ${extra_predicate}
    GROUP BY workspace_id, project_id, thread_id, id
) tt
LEFT ANY JOIN workspace_configurations wc FINAL
    ON tt.workspace_id = wc.workspace_id
WHERE tt.latest_status = 'active'
  AND tt.latest_updated_at < now64(6) - INTERVAL IF(wc.timeout_mark_thread_as_inactive > 0,
                                                     wc.timeout_mark_thread_as_inactive,
                                                     ${DEFAULT_TIMEOUT_SECONDS}) SECOND
GROUP BY tt.workspace_id, tt.project_id
ORDER BY min(tt.latest_updated_at)
LIMIT ${LIMIT}
SETTINGS log_comment = '${log_comment}'
FORMAT Null
SQL
}

# Build a SINGLE INSERT that emits all `threads × UPDATES_PER_THREAD` rows.
#
# All versions of one thread share `(workspace_id, project_id, thread_id, id)` —
# the ReplacingMergeTree sort key — so dedup at query time has real work to do.
# Within the INSERT we let ClickHouse's `max_insert_block_size` (~1M rows by
# default) split the stream into multiple parts naturally, so versions of the
# same thread end up in DIFFERENT parts. That reproduces the un-merged prod
# state the current query is designed for (OPIK-6519).
#
# Row layout (number ranges over [0, threads * UPDATES_PER_THREAD)):
#   thread_idx  = intDiv(number, UPDATES_PER_THREAD) + id_offset
#   update_idx  = number % UPDATES_PER_THREAD                 # 0 = oldest version
#   minutes_ago = base_minutes_ago + (UPDATES_PER_THREAD - 1 - update_idx) * UPDATE_DELTA_MIN
insert_workspace_sql() {
    local ws_id="$1"
    local threads="$2"
    local id_offset="$3"
    local project_id="$4"
    local status="$5"
    local base_minutes_ago="$6"

    local latest_idx=$(( UPDATES_PER_THREAD - 1 ))
    local total_rows=$(( threads * UPDATES_PER_THREAD ))
    local oldest_created_min=$(( base_minutes_ago + latest_idx * UPDATE_DELTA_MIN + 5 ))

    cat <<SQL
INSERT INTO trace_threads
  (id, thread_id, project_id, workspace_id, status,
   created_at, last_updated_at, created_by, last_updated_by,
   tags, source, environment)
SELECT
  toFixedString(
    leftPad(toString(intDiv(number, ${UPDATES_PER_THREAD}) + ${id_offset}), 36, '0'),
    36
  ) AS id,
  concat('t-', toString(intDiv(number, ${UPDATES_PER_THREAD}) + ${id_offset})) AS thread_id,
  toFixedString('${project_id}', 36) AS project_id,
  '${ws_id}' AS workspace_id,
  '${status}' AS status,
  now64(9) - INTERVAL ${oldest_created_min} MINUTE AS created_at,
  now64(6) - INTERVAL (${base_minutes_ago}
                      + (${latest_idx} - (number % ${UPDATES_PER_THREAD})) * ${UPDATE_DELTA_MIN}) MINUTE
                                                     AS last_updated_at,
  '${SEED_TAG}' AS created_by,
  '${SEED_TAG}' AS last_updated_by,
  [] AS tags,
  'sdk' AS source,
  '' AS environment
FROM numbers(${total_rows})
SETTINGS log_comment = '${SEED_TAG}_insert',
         max_insert_block_size = 500000,
         optimize_on_insert = 0  -- keep all versions; ReplacingMergeTree would otherwise dedup at INSERT
SQL
}

# ---- commands -------------------------------------------------------------

cmd_seed() {
    local total_hot=$(( HOT_THREADS * UPDATES_PER_THREAD ))
    echo ">> Seeding hot workspace ${HOT_WS}:"
    echo "   ${HOT_THREADS} threads × ${UPDATES_PER_THREAD} versions = ${total_hot} rows (latest version = now-2h)…"

    # Suspend background merges so ReplacingMergeTree doesn't collapse our
    # version history before we can probe. The prod query is designed for the
    # un-merged case (per the OPIK-6519 comment in TraceThreadDAO.java:154-158).
    if [ "${UPDATES_PER_THREAD}" -gt 1 ]; then
        echo "   (suspending merges on trace_threads to preserve version history)"
        ch "SYSTEM STOP MERGES trace_threads" >/dev/null
    fi

    ch "$(insert_workspace_sql "${HOT_WS}" "${HOT_THREADS}" 0 "${PROJECT_HOT}" "active" 120)" >/dev/null

    local id_offset=10000000000   # well above HOT_THREADS so ids stay unique
    for i in $(seq 1 "${NORMAL_WORKSPACES}"); do
        local suffix; suffix=$(printf '%012d' "$i")
        local ws="${NORMAL_WS_PREFIX}${suffix}"
        local project; project="22222222-2222-2222-2222-$(printf '%012d' "$i")"

        # Half stale-active (eligible for closure), half fresh (should be skipped).
        local stale_n=$(( NORMAL_THREADS_PER_WS / 2 ))
        local fresh_n=$(( NORMAL_THREADS_PER_WS - stale_n ))

        ch "$(insert_workspace_sql "$ws" "$stale_n" "$id_offset" "$project" "active" 90)" >/dev/null
        id_offset=$(( id_offset + stale_n + 1 ))
        ch "$(insert_workspace_sql "$ws" "$fresh_n" "$id_offset" "$project" "active" 1)" >/dev/null
        id_offset=$(( id_offset + fresh_n + 1 ))
    done

    echo ">> Seed complete. Row counts per workspace (top 5):"
    ch "SELECT workspace_id,
               count()                                              AS rows,
               uniqExact((workspace_id, project_id, thread_id, id)) AS unique_threads,
               count() / unique_threads                             AS avg_versions
        FROM trace_threads
        WHERE created_by = '${SEED_TAG}'
        GROUP BY workspace_id
        ORDER BY rows DESC
        LIMIT 5
        FORMAT PrettyCompact"

    echo ">> Parts holding seed data (more parts = unmerged versions = more dedup work):"
    ch "SELECT
            count() AS parts_with_seed,
            sum(rows) AS total_rows
        FROM system.parts
        WHERE table = 'trace_threads' AND active
        FORMAT PrettyCompact"
}

# Run each variant twice; the SECOND (warm-cache) reading is what gets reported.
# After the runs, pull query_log so we see read_rows + memory + duration.
cmd_probe() {
    local ts="$(date +%s)"
    local g_tag="${SEED_TAG}_global_${ts}"
    local p_tag="${SEED_TAG}_perws_${ts}"
    local ga_tag="${SEED_TAG}_global_argmax_${ts}"
    local pa_tag="${SEED_TAG}_perws_argmax_${ts}"

    echo ">> 4 variants × 2 runs each (cold + warm)"

    # LIMIT-BY (current production query)
    ch "$(probe_query_sql "" "${g_tag}_warmup")" >/dev/null
    ch "$(probe_query_sql "" "${g_tag}")" >/dev/null
    ch "$(probe_query_sql "AND workspace_id = '${HOT_WS}'" "${p_tag}_warmup")" >/dev/null
    ch "$(probe_query_sql "AND workspace_id = '${HOT_WS}'" "${p_tag}")" >/dev/null

    # argMax aggregation
    ch "$(probe_query_argmax_sql "" "${ga_tag}_warmup")" >/dev/null
    ch "$(probe_query_argmax_sql "" "${ga_tag}")" >/dev/null
    ch "$(probe_query_argmax_sql "AND workspace_id = '${HOT_WS}'" "${pa_tag}_warmup")" >/dev/null
    ch "$(probe_query_argmax_sql "AND workspace_id = '${HOT_WS}'" "${pa_tag}")" >/dev/null

    ch "SYSTEM FLUSH LOGS" >/dev/null

    echo
    echo ">> Warm-run comparison from system.query_log:"
    ch "
        SELECT
            multiIf(log_comment = '${g_tag}',  '1. limitby_global',
                    log_comment = '${p_tag}',  '2. limitby_per_ws',
                    log_comment = '${ga_tag}', '3. argmax_global',
                    log_comment = '${pa_tag}', '4. argmax_per_ws',
                    log_comment)                                    AS variant,
            query_duration_ms                                       AS dur_ms,
            formatReadableQuantity(read_rows)                       AS read_rows,
            formatReadableSize(read_bytes)                          AS read_bytes,
            formatReadableSize(memory_usage)                        AS memory,
            result_rows
        FROM system.query_log
        WHERE log_comment IN ('${g_tag}', '${p_tag}', '${ga_tag}', '${pa_tag}')
          AND type = 'QueryFinish'
        ORDER BY variant
        FORMAT PrettyCompact"
}

cmd_explain() {
    echo ">> EXPLAIN indexes=1 — GLOBAL"
    ch "EXPLAIN indexes = 1
        $(probe_query_sql "" "explain_global" | sed 's/FORMAT Null//')
        FORMAT TSVRaw"
    echo
    echo ">> EXPLAIN indexes=1 — PER-WORKSPACE (hot ws)"
    ch "EXPLAIN indexes = 1
        $(probe_query_sql "AND workspace_id = '${HOT_WS}'" "explain_perws" | sed 's/FORMAT Null//')
        FORMAT TSVRaw"
    echo
    echo ">> EXPLAIN PIPELINE — GLOBAL"
    ch "EXPLAIN PIPELINE
        $(probe_query_sql "" "explain_global_pipe" | sed 's/FORMAT Null//')
        FORMAT TSVRaw"
}

cmd_cleanup() {
    echo ">> Resuming background merges on trace_threads (if previously stopped)…"
    ch "SYSTEM START MERGES trace_threads" >/dev/null 2>&1 || true
    echo ">> Deleting synthetic rows (created_by='${SEED_TAG}')…"
    ch "ALTER TABLE trace_threads DELETE WHERE created_by = '${SEED_TAG}'"
    echo ">> Mutation queued. Check with:"
    echo "   curl -s '${CH_URL}/?database=${CH_DB}&user=${CH_USER}&password=${CH_PASS}' \\"
    echo "        --data-binary \"SELECT command, is_done FROM system.mutations WHERE table='trace_threads' ORDER BY create_time DESC LIMIT 5 FORMAT PrettyCompact\""
}

cmd_all() {
    cmd_seed
    echo
    cmd_probe
    echo
    cmd_explain
}

case "${1:-}" in
    seed)    cmd_seed ;;
    probe)   cmd_probe ;;
    explain) cmd_explain ;;
    cleanup) cmd_cleanup ;;
    all)     cmd_all ;;
    *)
        echo "usage: $0 {seed|probe|explain|cleanup|all}" >&2
        exit 1
        ;;
esac