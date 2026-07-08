#!/bin/bash
set -euo pipefail

# Provision the Agent Insights read-only free-form SQL ClickHouse user, settings profile, grants and row policies.
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
ch_url="http://${ch_host}:${ch_port}/?user=${ch_admin_user}&password=${ch_admin_pass}"

echo "Provisioning Agent Insights read-only ClickHouse user '${ro_user}' on ${ch_host}:${ch_port}/${ch_db}..."

statements=(
    "CREATE USER IF NOT EXISTS ${ro_user} IDENTIFIED BY '${ro_pass}'"
    "CREATE SETTINGS PROFILE IF NOT EXISTS comet_llm_readonly_freeform_sql_profile SETTINGS readonly = 1, max_execution_time = 180, max_memory_usage = 8589934592, max_result_rows = 100000, result_overflow_mode = 'throw', max_rows_to_read = 100000000, read_overflow_mode = 'throw', max_concurrent_queries_for_user = 5, SQL_workspace_id = '' CHANGEABLE_IN_READONLY, SQL_project_id = '' CHANGEABLE_IN_READONLY TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.spans TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.traces TO ${ro_user}"
    "GRANT SELECT ON ${ch_db}.authored_feedback_scores TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS spans_workspace_project_isolation ON ${ch_db}.spans FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS traces_workspace_project_isolation ON ${ch_db}.traces FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
    "CREATE ROW POLICY IF NOT EXISTS authored_feedback_scores_workspace_project_isolation ON ${ch_db}.authored_feedback_scores FOR SELECT USING workspace_id = getSetting('SQL_workspace_id') AND project_id = getSetting('SQL_project_id') AS RESTRICTIVE TO ${ro_user}"
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

echo "Agent Insights read-only ClickHouse user provisioned."
