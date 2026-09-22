#!/bin/bash
set -euo pipefail

# Provision the read-only free-form SQL ClickHouse users, settings profiles, grants and row policies.
#
# Two users, both created together, deliberately separate accounts (see OPIK-8329 design 8.11):
#   - Agent Insights - traces/spans/authored_feedback_scores, all bound to workspace AND project.
#   - Extended free-form SQL, Custom Charts being its only consumer today - the same three tables plus
#     experiments, experiment_items, dataset_items, feedback_scores and trace_threads. traces/spans keep a project
#     bound but an optional one; everything else is workspace-bound only, authored_feedback_scores included. That
#     last policy differs from Agent Insights' and is why this cannot be one shared user: relaxing it in place
#     would widen the existing feature's reach across projects.
#
# Opt-in: only runs when TOGGLE_OLLIE_ENABLED=true; otherwise it's a no-op so default installs are untouched.
# Neither account depends on TOGGLE_CUSTOM_CHARTS_WORKSPACES: both are inert without a caller, and tying an
# account's existence to a toggle only means flipping that toggle needs a redeploy before it takes effect.
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
toggle="${TOGGLE_OLLIE_ENABLED:-false}"
toggle="${toggle#\"}"
toggle="${toggle%\"}"
toggle=$(printf '%s' "$toggle" | tr '[:upper:]' '[:lower:]')
if [ "$toggle" != "true" ]; then
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
ext_user="${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_USER:-comet_readonly_freeform_extended_sql_user}"
ext_pass="${ANALYTICS_DB_READ_ONLY_FREEFORM_EXTENDED_SQL_PASS:-opik}"
ch_url="http://${ch_host}:${ch_port}/?user=${ch_admin_user}&password=${ch_admin_pass}"

# One profile for both accounts: the limits are the same and there is no reason to maintain two copies of them.
# If the two ever need different caps, create a second profile then and point one user at it - that is a single
# statement. Until then, note that tuning this changes the safety envelope of BOTH features at once.
profile_settings="readonly = 1, max_execution_time = 180, max_memory_usage = 8589934592, max_result_rows = 100000, result_overflow_mode = 'throw', max_rows_to_read = 100000000, read_overflow_mode = 'throw', max_concurrent_queries_for_user = 5, use_skip_indexes_if_final = 1, SQL_workspace_id = '' CHANGEABLE_IN_READONLY, SQL_project_id = '' CHANGEABLE_IN_READONLY"

# Row policy filters. All three bind workspace_id and differ only in how they treat the project. The policies
# cannot be shared between the users the way the grants are, because for every table they have in common the two
# accounts need a different one of these.
ws_and_project="workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id')"
ws_only="workspace_id = getSetting('SQL_workspace_id')"
# '*' means every project in the workspace, so the caller picks the scope per request. getSetting() is a
# query-time constant, so ClickHouse folds the comparison before index analysis and the
# (workspace_id, project_id, ...) prefix still prunes - measured at 51.44k rows read against 51.42k for a plain
# equality. The sentinel is '*' rather than '' because the profile defaults the setting to '': an empty value
# matches neither branch and returns nothing, so a dropped setting fails closed instead of widening to the
# workspace.
ws_and_optional_project="workspace_id = getSetting('SQL_workspace_id') AND (getSetting('SQL_project_id') = '*' OR project_id = getSetting('SQL_project_id'))"

echo "Provisioning read-only ClickHouse users '${ro_user}' and '${ext_user}' on ${ch_host}:${ch_port}/${ch_db}..."

statements=(
    "CREATE USER IF NOT EXISTS ${ro_user} IDENTIFIED BY '${ro_pass}'"
    "CREATE USER IF NOT EXISTS ${ext_user} IDENTIFIED BY '${ext_pass}'"
    "CREATE SETTINGS PROFILE IF NOT EXISTS comet_llm_readonly_freeform_sql_profile SETTINGS ${profile_settings} TO ${ro_user}, ${ext_user}"

    # Both accounts read these three.
    "GRANT SELECT ON ${ch_db}.spans TO ${ro_user}, ${ext_user}"
    "GRANT SELECT ON ${ch_db}.traces TO ${ro_user}, ${ext_user}"
    "GRANT SELECT ON ${ch_db}.authored_feedback_scores TO ${ro_user}, ${ext_user}"

    # Agent Insights: every table bound to workspace AND project.
    "CREATE ROW POLICY IF NOT EXISTS spans_workspace_project_isolation ON ${ch_db}.spans FOR SELECT USING ${ws_and_project} AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS traces_workspace_project_isolation ON ${ch_db}.traces FOR SELECT USING ${ws_and_project} AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS authored_feedback_scores_workspace_project_isolation ON ${ch_db}.authored_feedback_scores FOR SELECT USING ${ws_and_project} AS RESTRICTIVE TO ${ro_user}"

    # Extended account: traces and spans keep a project bound, but an optional one.
    "CREATE ROW POLICY IF NOT EXISTS spans_freeform_extended_sql_workspace_project_isolation ON ${ch_db}.spans FOR SELECT USING ${ws_and_optional_project} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS traces_freeform_extended_sql_workspace_project_isolation ON ${ch_db}.traces FOR SELECT USING ${ws_and_optional_project} AS RESTRICTIVE TO ${ext_user}"

    # Extended account: five more tables, all workspace-only. dataset_items has no project_id column at all, and
    # project_id is empty on ~77% of experiments / ~69% of experiment_items in production, so a project bound on
    # those would silently hide most rows rather than fail. authored_feedback_scores is workspace-only here too,
    # which is the policy difference that makes this a second account rather than extra grants on the first.
    "GRANT SELECT ON ${ch_db}.feedback_scores TO ${ext_user}"
    "GRANT SELECT ON ${ch_db}.experiments TO ${ext_user}"
    "GRANT SELECT ON ${ch_db}.experiment_items TO ${ext_user}"
    "GRANT SELECT ON ${ch_db}.dataset_items TO ${ext_user}"
    "GRANT SELECT ON ${ch_db}.trace_threads TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS authored_feedback_scores_freeform_extended_sql_workspace_isolation ON ${ch_db}.authored_feedback_scores FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS feedback_scores_freeform_extended_sql_workspace_isolation ON ${ch_db}.feedback_scores FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS experiments_freeform_extended_sql_workspace_isolation ON ${ch_db}.experiments FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS experiment_items_freeform_extended_sql_workspace_isolation ON ${ch_db}.experiment_items FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS dataset_items_freeform_extended_sql_workspace_isolation ON ${ch_db}.dataset_items FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
    "CREATE ROW POLICY IF NOT EXISTS trace_threads_freeform_extended_sql_workspace_isolation ON ${ch_db}.trace_threads FOR SELECT USING ${ws_only} AS RESTRICTIVE TO ${ext_user}"
)

for stmt in "${statements[@]}"; do
    response=$(curl -sS -w $'\n%{http_code}' "$ch_url" --data-binary "$stmt")
    http_code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [ "$http_code" != "200" ]; then
        echo "Failed to provision read-only CH user (statement starting '${stmt%% *}...'): ${body}" >&2
        exit 1
    fi
done

echo "Read-only ClickHouse user(s) provisioned."
