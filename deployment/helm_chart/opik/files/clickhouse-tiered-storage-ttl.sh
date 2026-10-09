#!/usr/bin/env bash
# Tiered-storage TTL Job: puts each table on the tiered_replicated policy with a move-to-cold TTL,
# idempotently. With TTL_MATERIALIZE=true it also backfills: MATERIALIZE TTL, so parts written
# before the TTL existed move to cold too.
#
# Env (set by templates/clickhouse-tiered-storage-ttl-job.yaml):
#   SOURCE_COUNT, SRC<i>_<var>  the backend's env sources, in its order; a later one with a value wins
#   TTL_TABLES                  "name:weeks ..."
#   TTL_MATERIALIZE             true | false
set -euo pipefail

VARS="ANALYTICS_DB_HOST ANALYTICS_DB_DATABASE_NAME ANALYTICS_DB_MIGRATIONS_USER ANALYTICS_DB_MIGRATIONS_PASS"
IDENT='^[A-Za-z_][A-Za-z0-9_]*$'

die() { echo "ERROR: $*" >&2; exit 1; }
is_count() { [[ "$1" =~ ^[0-9]+$ ]]; }

is_count "${SOURCE_COUNT:-}" || die "SOURCE_COUNT must be a non-negative integer."
case "${TTL_MATERIALIZE:-}" in true|false) ;; *) die "TTL_MATERIALIZE must be true or false." ;; esac
for spec in ${TTL_TABLES:-}; do
  [[ "${spec%%:*}" =~ $IDENT && "${spec#*:}" =~ ^[1-9][0-9]*$ ]] || die "TTL_TABLES entry '${spec}' is not name:weeks."
done

# As with envFrom, a later source with a value wins.
for v in $VARS; do
  for ((i = 0; i < SOURCE_COUNT; i++)); do
    n="SRC${i}_${v}"
    if [ -n "${!n:-}" ]; then printf -v "$v" '%s' "${!n}"; fi
  done
done
for v in $VARS; do
  [ -n "${!v:-}" ] || die "$v is not set; it comes from the opik-backend env."
done
DB="$ANALYTICS_DB_DATABASE_NAME"
[[ "$DB" =~ $IDENT ]] || die "ANALYTICS_DB_DATABASE_NAME is not a plain identifier."
# Credentials via the client's env, not argv.
export CLICKHOUSE_USER="$ANALYTICS_DB_MIGRATIONS_USER" CLICKHOUSE_PASSWORD="$ANALYTICS_DB_MIGRATIONS_PASS"
ch() { clickhouse-client --host "$ANALYTICS_DB_HOST" --query "$1" < /dev/null; }

replicas=$(ch "SELECT count() FROM system.clusters WHERE cluster = getMacro('cluster')")
[ "${replicas:-0}" -gt 0 ] || die "no replicas found for the cluster macro."
policy_replica_count=$(ch "SELECT count() FROM clusterAllReplicas(getMacro('cluster'), system.storage_policies) WHERE policy_name = 'tiered_replicated' AND volume_name = 'cold'")
[ "$policy_replica_count" = "$replicas" ] || die "tiered_replicated policy with a cold volume is on ${policy_replica_count} of ${replicas} replicas."

apply() {  # table weeks
  local t="$1" w="$2" exists policy same want want_sql matching_replica_count
  exists=$(ch "EXISTS TABLE ${DB}.${t}")
  case "$exists" in
    1) ;;
    0) echo "skip ${DB}.${t}: table does not exist"; return 0 ;;
    *) echo "ERROR: could not tell whether ${DB}.${t} exists: '${exists}'" >&2; return 1 ;;
  esac
  want="id_at + toIntervalWeek(${w}) TO DISK 'cold'"
  want_sql="'id_at + toIntervalWeek(${w}) TO DISK \\'cold\\''"
  policy=$(ch "SELECT storage_policy FROM system.tables WHERE database = '${DB}' AND name = '${t}'")
  if [ "$policy" != "tiered_replicated" ]; then
    echo "${DB}.${t}: storage_policy ${policy} -> tiered_replicated"
    ch "ALTER TABLE ${DB}.${t} ON CLUSTER '{cluster}' MODIFY SETTING storage_policy = 'tiered_replicated'"
  fi
  # Compared in SQL: the client escapes quotes in its output.
  same=$(ch "SELECT extract(create_table_query, 'TTL (.+?) SETTINGS ') = ${want_sql} FROM system.tables WHERE database = '${DB}' AND name = '${t}'")
  if [ "$same" != "1" ]; then
    echo "${DB}.${t}: TTL -> ${want}"
    # Existing parts are left alone here; materialize() backfills them when enabled.
    ch "ALTER TABLE ${DB}.${t} ON CLUSTER '{cluster}' MODIFY TTL id_at + INTERVAL ${w} WEEK TO DISK 'cold' SETTINGS materialize_ttl_after_modify = 0"
  fi
  # Settings are per replica, so check every one.
  matching_replica_count=$(ch "SELECT count() FROM clusterAllReplicas(getMacro('cluster'), system.tables) WHERE database = '${DB}' AND name = '${t}' AND storage_policy = 'tiered_replicated' AND extract(create_table_query, 'TTL (.+?) SETTINGS ') = ${want_sql}")
  [ "$matching_replica_count" = "$replicas" ] || { echo "ERROR: ${DB}.${t} matches on ${matching_replica_count} of ${replicas} replicas." >&2; return 1; }
  echo "OK: ${DB}.${t} tiered_replicated, TTL ${want}, on all ${replicas} replicas"
  [ "$TTL_MATERIALIZE" = true ] || return 0
  materialize "$t" "$w"
}

# Parts written before the TTL was set carry no TTL info, so the mover never picks them up.
# Only those trigger a run, and only when none is in flight: a sync after the backfill is a no-op.
materialize() {  # table weeks
  local t="$1" w="$2" pending running
  pending=$(ch "SELECT count() FROM clusterAllReplicas(getMacro('cluster'), system.parts) WHERE database = '${DB}' AND table = '${t}' AND active AND NOT has(move_ttl_info.expression, 'id_at + toIntervalWeek(${w})')")
  is_count "$pending" || { echo "ERROR: could not count ${DB}.${t} parts without the TTL: '${pending}'" >&2; return 1; }
  if [ "$pending" = 0 ]; then
    echo "OK: ${DB}.${t} every part carries the TTL; nothing to materialize"
    return 0
  fi
  running=$(ch "SELECT count() FROM clusterAllReplicas(getMacro('cluster'), system.mutations) WHERE database = '${DB}' AND table = '${t}' AND NOT is_done AND command LIKE '%MATERIALIZE TTL%'")
  is_count "$running" || { echo "ERROR: could not read ${DB}.${t} mutations: '${running}'" >&2; return 1; }
  if [ "$running" != 0 ]; then
    echo "skip ${DB}.${t}: a MATERIALIZE TTL is still running (${pending} parts without the TTL)"
    return 0
  fi
  echo "${DB}.${t}: MATERIALIZE TTL (${pending} parts without the TTL, all replicas)"
  # Async: the mutation and the moves after it run in the background.
  ch "ALTER TABLE ${DB}.${t} ON CLUSTER '{cluster}' MATERIALIZE TTL"
}

[ -n "${TTL_TABLES:-}" ] || { echo "no tables configured"; exit 0; }
for spec in $TTL_TABLES; do
  apply "${spec%%:*}" "${spec#*:}"
done
