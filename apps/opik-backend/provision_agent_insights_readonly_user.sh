#!/bin/bash
set -euo pipefail

# Provision the read-only free-form SQL ClickHouse users, settings profiles, grants and row policies.
#
# Two deliberately separate accounts, each behind its own flag:
#   - Agent Insights (TOGGLE_OLLIE_ENABLED) - traces/spans/authored_feedback_scores, all bound to workspace AND project.
#   - Extended free-form SQL (ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER_ENABLED, also requires
#     TOGGLE_OLLIE_ENABLED) - the same three tables plus experiments, experiment_items, dataset_items, dataset_item_versions,
#     feedback_scores and trace_threads.
#     traces/spans keep a project bound but an optional one; everything else is workspace-bound only,
#     authored_feedback_scores included.
#
# Isolation is the row policies. Each table read through a Distributed wrapper gets the same policy, and the grant, on
# its local table as well (traces_local, spans_local): a distributed read evaluates the initial user's policies on the
# shard against the local table, so a policy on the wrapper alone does not scope it. Both are created by name ahead of
# the wrap, so the table the cutover renames into place is covered from its first read. A policy always precedes its
# grant: a granted table with no policy for the user is readable in full.
#
# Opt-in: only runs when TOGGLE_OLLIE_ENABLED=true; otherwise it's a no-op so default installs are untouched.
# This is the single local copy of the DDL, shared by docker-compose (backend container, between run_db_migrations.sh
# and entrypoint.sh) and scripts/dev-runner.sh. It mirrors the prod provisioning owned by OPIK-6846 — keep them in
# sync. Must run AFTER the analytics migrations (the GRANT/ROW POLICY statements reference the opik tables) and BEFORE
# the backend starts (so the clickhouse-readonly-freeform-sql health check finds the user). The SQL_ custom-settings
# prefix must already be registered in the ClickHouse server config (additional_config.xml locally; OPIK-6846 in prod),
# otherwise the settings profile DDL is rejected.

# Mirror Dropwizard's YAML-boolean truthy semantics: docker-compose substitution of `${VAR:-"true"}`
# passes the literal quote chars through, and on the Java side YAML re-parse coerces
# "true"/"True"/"TRUE" to boolean true — a strict `[ "$VAR" != "true" ]` would silently skip
# provisioning while the backend boots with the feature armed. `tr` rather than bash 4 `${var,,}`
# keeps this runnable on macOS bash 3.2 for scripts/dev-runner.sh.
is_true() {
    local value="${1#\"}"
    value="${value%\"}"
    [ "$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')" = "true" ]
}

if ! is_true "${TOGGLE_OLLIE_ENABLED:-false}"; then
    echo "Agent Insights disabled; skipping read-only ClickHouse user provisioning."
    exit 0
fi

ch_host="${ANALYTICS_DB_HOST:-localhost}"
ch_port="${ANALYTICS_DB_PORT:-8123}"
ch_admin_user="${ANALYTICS_DB_USERNAME:-opik}"
ch_admin_pass="${ANALYTICS_DB_PASS:-opik}"
ch_db="${ANALYTICS_DB_DATABASE_NAME:-opik}"
ro_user="${ANALYTICS_DB_READ_ONLY_FREEFORM_SQL_USER:-comet_readonly_freeform_sql_user}"
ro_pass="${ANALYTICS_DB_READ_ONLY_FREEFORM_SQL_PASS:-opik}"
ro_extended_user="${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER:-comet_readonly_freeform_extended_sql_user}"
ro_extended_pass="${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_PASS:-opik}"
ch_url="http://${ch_host}:${ch_port}/?user=${ch_admin_user}&password=${ch_admin_pass}"

# Settings pinned CONST on the profile: each one can, on some version or configuration, stop row policies applying
# or let one account's reads affect another's, so a cluster-wide default or version change must not reach these
# accounts. The two count shortcuts are off, and a single-shard Distributed read stays on the initiator, so every
# read leaves a policy-filtered read in the plan, which the backend's post-run check requires. Re-test them on every ClickHouse upgrade (FreeFormSqlRowPolicyConformanceTest).
pinned_settings="readonly = 1 CONST, allow_ddl = 0 CONST, serialize_query_plan = 0 CONST, make_distributed_plan = 0 CONST, enable_parallel_replicas = 0 CONST, max_parallel_replicas = 1 CONST, use_query_cache = 0 CONST, query_cache_share_between_users = 0 CONST, use_query_condition_cache = 0 CONST, enable_analyzer = 1 CONST, apply_row_policy_after_final = 1 CONST, allow_introspection_functions = 0 CONST, optimize_trivial_count_query = 0 CONST, optimize_use_implicit_projections = 0 CONST, prefer_localhost_replica = 1 CONST"
profile_settings="${pinned_settings}, max_execution_time = 180, max_memory_usage = 8589934592, max_result_rows = 100000, result_overflow_mode = 'throw', max_rows_to_read = 100000000, read_overflow_mode = 'throw', max_concurrent_queries_for_user = 5, use_skip_indexes_if_final = 1, SQL_workspace_id = '' CHANGEABLE_IN_READONLY, SQL_project_id = '' CHANGEABLE_IN_READONLY"

echo "Provisioning Agent Insights read-only ClickHouse user '${ro_user}' on ${ch_host}:${ch_port}/${ch_db}..."

statements=(
    "CREATE USER IF NOT EXISTS ${ro_user} IDENTIFIED BY '${ro_pass}'"
    "CREATE SETTINGS PROFILE IF NOT EXISTS comet_llm_readonly_freeform_sql_profile SETTINGS ${profile_settings} TO ${ro_user}"
    # ALTER replaces the whole settings list, so an existing profile picks up the pins without being re-created.
    "ALTER SETTINGS PROFILE comet_llm_readonly_freeform_sql_profile SETTINGS ${profile_settings}"
    # Likewise for the user: its settings become just the profile, dropping any user-level setting left by an earlier
    # provisioning (a user-level additional_table_filters on a table with a policy fails every query).
    "ALTER USER ${ro_user} SETTINGS PROFILE 'comet_llm_readonly_freeform_sql_profile'"
)

# policy_statements USER SUFFIX PREDICATE TABLE...: one RESTRICTIVE policy per table, then its grant, plus the same
# pair on the local table behind traces and spans. Each pair is also recorded for the checks below.
local_tables="traces spans"
expected=()
policy_statements() {
    local user=$1 suffix=$2 predicate=$3 table
    shift 3
    for table in "$@"; do
        local names="${table}"
        if [[ " ${local_tables} " == *" ${table} "* ]]; then
            names="${table} ${table}_local"
        fi
        for name in ${names}; do
            statements+=(
                "CREATE ROW POLICY IF NOT EXISTS ${name}_${suffix} ON ${ch_db}.${name} FOR SELECT USING ${predicate} AS RESTRICTIVE TO ${user}"
                "GRANT SELECT ON ${ch_db}.${name} TO ${user}"
            )
            expected+=("${user}"$'\t'"${name}")
        done
    done
}
workspace="workspace_id = getSetting('SQL_workspace_id')"
workspace_project="${workspace} AND project_id = getSetting('SQL_project_id')"
workspace_optional_project="${workspace} AND (getSetting('SQL_project_id') = '*' OR project_id = getSetting('SQL_project_id'))"

# Agent Insights: three tables, every one bound to workspace AND project.
policy_statements "${ro_user}" workspace_project_isolation "${workspace_project}" spans traces authored_feedback_scores

# Extended account: its own policies on all nine tables it reads, as production declares them.
#
# Every table carrying project_id in its primary key keeps a project bound, but an optional one: '*' means every
# project in the workspace, so the caller picks the scope per request. The sentinel is '*' rather than '' because
# the profile defaults the setting to '': an empty value matches neither branch and returns nothing, so a dropped
# setting fails closed instead of widening to the whole workspace.
#
# experiments, experiment_items, dataset_items and dataset_item_versions key off the workspace instead, so they stay workspace-only:
# an experiment or a dataset can span projects, and binding one to a project would drop the rows living
# elsewhere and under-report silently rather than erroring.
if is_true "${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER_ENABLED:-false}"; then
    echo "Provisioning extended free-form SQL read-only ClickHouse user '${ro_extended_user}'..."
    statements+=(
        "CREATE USER IF NOT EXISTS ${ro_extended_user} IDENTIFIED BY '${ro_extended_pass}'"
        "ALTER USER ${ro_extended_user} SETTINGS PROFILE 'comet_llm_readonly_freeform_sql_profile'"
    )
    policy_statements "${ro_extended_user}" freeform_extended_sql_isolation "${workspace_optional_project}" \
        spans traces authored_feedback_scores feedback_scores trace_threads
    policy_statements "${ro_extended_user}" freeform_extended_sql_isolation "${workspace}" \
        experiments experiment_items dataset_items dataset_item_versions
fi

for stmt in "${statements[@]}"; do
    response=$(curl -sS -w $'\n%{http_code}' "$ch_url" --data-binary "$stmt")
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [ "$http_code" != "200" ]; then
        echo "Failed to provision read-only CH user (statement starting '${stmt%% *}...'): ${body}" >&2
        exit 1
    fi
done

# query URL SQL: runs SQL at URL, prints the body, fails the provisioning on any error.
query() {
    local response http_code
    response=$(curl -sS -w $'\n%{http_code}' "$1" --data-binary "$2")
    http_code="${response##*$'\n'}"
    if [ "$http_code" != "200" ]; then
        echo "Read-only CH user check failed (query starting '${2:0:60}...'): ${response%$'\n'*}" >&2
        exit 1
    fi
    printf '%s' "${response%$'\n'*}"
}

# Check 1: every table each account can SELECT has a RESTRICTIVE policy for it, and every expected policy exists.
# A granted table without one is readable in full, so this is checked from the grants side as well.
for pair in "${expected[@]}"; do
    user="${pair%%$'\t'*}" table="${pair#*$'\t'}"
    found=$(query "$ch_url" "SELECT count() FROM system.row_policies WHERE database = '${ch_db}' AND table = '${table}' AND is_restrictive AND has(apply_to_list, '${user}')")
    if [ "$found" != "1" ]; then
        echo "Read-only CH user check failed: no row policy for '${user}' on ${ch_db}.${table}" >&2
        exit 1
    fi
done
for user in ${ro_user} $(is_true "${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER_ENABLED:-false}" && echo "${ro_extended_user}"); do
    unpoliced=$(query "$ch_url" "SELECT arrayStringConcat(groupArray(table), ', ') FROM system.grants WHERE user_name = '${user}' AND access_type = 'SELECT' AND database = '${ch_db}' AND table NOT IN (SELECT table FROM system.row_policies WHERE database = '${ch_db}' AND has(apply_to_list, '${user}'))")
    if [ -n "$unpoliced" ]; then
        echo "Read-only CH user check failed: '${user}' can SELECT ${unpoliced} with no row policy" >&2
        exit 1
    fi
done

# Check 2: as each account, a workspace and project no one has must see no row in any existing table it reads, so
# a policy that exists but does not take effect fails here. Reads through the Distributed tables take the account's
# own read path. An empty table passes trivially: it has nothing to expose.
probe_scope="SQL_workspace_id = 'opik-scope-probe-no-workspace', SQL_project_id = 'opik-scope-probe-no-project'"
for pair in "${expected[@]}"; do
    user="${pair%%$'\t'*}" table="${pair#*$'\t'}"
    [ "$(query "$ch_url" "EXISTS TABLE ${ch_db}.${table}")" = "1" ] || continue
    pass="${ro_pass}"
    [ "$user" = "${ro_extended_user}" ] && pass="${ro_extended_pass}"
    visible=$(query "http://${ch_host}:${ch_port}/?user=${user}&password=${pass}" "SELECT count() FROM ${ch_db}.${table} SETTINGS ${probe_scope}")
    if [ "$visible" != "0" ]; then
        echo "Read-only CH user check failed: '${user}' sees ${visible} rows of ${ch_db}.${table} outside any workspace" >&2
        exit 1
    fi
done

echo "Read-only ClickHouse user(s) provisioned and checked."
