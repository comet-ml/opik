#!/bin/bash
set -euo pipefail

# Provision the read-only free-form SQL ClickHouse users, settings profiles, grants and row policies.
#
# Two users, deliberately separate (see OPIK-8329 design 8.11):
#   - Agent Insights (always, when TOGGLE_OLLIE_ENABLED=true) - traces/spans/authored_feedback_scores, all bound to
#     workspace AND project.
#   - Extended free-form SQL, Custom Charts being its only consumer today (additionally, when
#     TOGGLE_CUSTOM_CHARTS_WORKSPACES is non-empty) - the same three tables plus
#     experiments, experiment_items, dataset_items, feedback_scores and trace_threads. traces/spans stay
#     project-bound; everything else is workspace-bound only, authored_feedback_scores included. That last policy
#     differs from Agent Insights' and is why this cannot be one shared user: relaxing it in place would widen the
#     existing feature's reach across projects.
#
# Opt-in: only runs when TOGGLE_OLLIE_ENABLED=true; otherwise it's a no-op so default installs are untouched.
# Custom Charts rides the same endpoint, which is itself gated on ollieEnabled, so it is never provisioned alone.
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
charts_workspaces="${TOGGLE_CUSTOM_CHARTS_WORKSPACES:-}"
charts_workspaces="${charts_workspaces#\"}"
charts_workspaces="${charts_workspaces%\"}"
ch_url="http://${ch_host}:${ch_port}/?user=${ch_admin_user}&password=${ch_admin_pass}"

echo "Provisioning Agent Insights read-only ClickHouse user '${ro_user}' on ${ch_host}:${ch_port}/${ch_db}..."

statements=(
    "CREATE USER IF NOT EXISTS ${ro_user} IDENTIFIED BY '${ro_pass}'"
    "CREATE SETTINGS PROFILE IF NOT EXISTS comet_llm_readonly_freeform_sql_profile SETTINGS readonly = 1, max_execution_time = 180, max_memory_usage = 8589934592, max_result_rows = 100000, result_overflow_mode = 'throw', max_rows_to_read = 100000000, read_overflow_mode = 'throw', max_concurrent_queries_for_user = 5, use_skip_indexes_if_final = 1, SQL_workspace_id = '' CHANGEABLE_IN_READONLY, SQL_project_id = '' CHANGEABLE_IN_READONLY TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.spans TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.traces TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.authored_feedback_scores TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS spans_workspace_project_isolation ON ${ch_db}.spans FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS traces_workspace_project_isolation ON ${ch_db}.traces FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS authored_feedback_scores_workspace_project_isolation ON ${ch_db}.authored_feedback_scores FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
)

# Extended free-form SQL user. Provisioned only when at least one workspace is allowlisted, so an install that
# never turns the feature on carries no extra account. Grants cover the five tables Agent Insights lacks; the row
# policies are workspace-only on everything except traces/spans, which stay project-bound (A3).
if [ -n "$charts_workspaces" ]; then
    echo "Provisioning extended free-form SQL read-only ClickHouse user '${ext_user}'..."
    statements+=(
        "CREATE USER IF NOT EXISTS ${ext_user} IDENTIFIED BY '${ext_pass}'"
        "CREATE SETTINGS PROFILE IF NOT EXISTS comet_readonly_freeform_extended_sql_profile SETTINGS readonly = 1, max_execution_time = 180, max_memory_usage = 8589934592, max_result_rows = 100000, result_overflow_mode = 'throw', max_rows_to_read = 100000000, read_overflow_mode = 'throw', max_concurrent_queries_for_user = 5, use_skip_indexes_if_final = 1, SQL_workspace_id = '' CHANGEABLE_IN_READONLY, SQL_project_id = '' CHANGEABLE_IN_READONLY TO ${ext_user}"
    )
    # traces and spans keep the workspace+project policy; everything else is workspace-only. dataset_items has no
    # project_id column at all, and project_id is empty on ~77% of experiments / ~69% of experiment_items in
    # production, so a project-bound policy on those would silently hide most rows rather than fail.
    for tbl in spans traces; do
        statements+=(
            "GRANT SELECT ON ${ch_db}.${tbl} TO ${ext_user}"
            "CREATE ROW POLICY IF NOT EXISTS ${tbl}_freeform_extended_sql_workspace_project_isolation ON ${ch_db}.${tbl} FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ext_user}"
        )
    done
    for tbl in authored_feedback_scores feedback_scores experiments experiment_items dataset_items trace_threads; do
        statements+=(
            "GRANT SELECT ON ${ch_db}.${tbl} TO ${ext_user}"
            "CREATE ROW POLICY IF NOT EXISTS ${tbl}_freeform_extended_sql_workspace_isolation ON ${ch_db}.${tbl} FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AS RESTRICTIVE TO ${ext_user}"
        )
    done
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

echo "Read-only ClickHouse user(s) provisioned."
