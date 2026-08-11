#!/bin/bash
# Re-baseline a Liquibase changelog: record pending changesets as applied WITHOUT executing them.
#
# Use this when the schema is intact but the Liquibase ledger (DATABASECHANGELOG) is empty or
# incomplete — for example after a volume restore that recovered the data but not the ledger.
# In that state a starting replica treats every changeset as pending and replays them against
# tables that already exist, which fails and leaves the replica crashlooping.
#
# This wraps Dropwizard's `fast-forward --all`, which calls Liquibase changelogSync. It writes
# ledger rows only; it never runs DDL and never touches your data.
#
# WARNING: this asserts "the schema already matches the changelog". If that is not true, the
# missing migrations are marked applied and silently never run — the deployment can then never
# build its schema, and no later run reports a problem. For ClickHouse that precondition is
# checked below rather than left to the operator; for MySQL it cannot be (no client in the image),
# which is why --database db refuses unless you force it.

set -euo pipefail

DATABASE="dbAnalytics"
ASSUME_YES="false"
DRY_RUN="false"
CONFIG="config.yml"
FORCE_UNVERIFIED="false"

# A recovered schema is at head, so its table count is on the order of the changeset count. A
# handful of tables against ~150 pending changesets means the schema is not built, and syncing
# would strand it there permanently. Ratio rather than an absolute floor: changesets outnumber
# tables (many are ALTERs), so this only has to separate "roughly complete" from "nearly empty".
MIN_TABLES_PER_PENDING_CHANGESET_PERCENT=10

usage() {
  cat <<'USAGE'
Usage: ./rebaseline_db_changelog.sh [options]

Marks pending changesets as applied without executing them, for a database whose schema is
already built but whose Liquibase ledger was lost or truncated.

Options:
  -d, --database <name>   Which changelog to re-baseline: dbAnalytics (ClickHouse, default)
                          or db (MySQL).
  -c, --config <path>     Dropwizard config file. Default: config.yml
  -n, --dry-run           Show what would be marked as applied, then exit without writing.
  -y, --yes               Skip the confirmation prompt. For non-interactive runs only.
                          The schema-at-head check still runs and still aborts.
      --force-unverified  Proceed even though the schema cannot be verified (MySQL, or an
                          unreachable ClickHouse). You are asserting the schema is at head;
                          if it is not, the missing migrations are permanently lost.
  -h, --help              Show this help.

Examples:
  # Inspect what is pending, change nothing
  ./rebaseline_db_changelog.sh --dry-run

  # Re-baseline the ClickHouse ledger (verifies the schema is built before writing)
  ./rebaseline_db_changelog.sh

  # Re-baseline the MySQL ledger — unverifiable, so it must be forced
  ./rebaseline_db_changelog.sh --database db --force-unverified
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--database) DATABASE="${2:-}"; shift 2 ;;
    -c|--config)   CONFIG="${2:-}"; shift 2 ;;
    -n|--dry-run)  DRY_RUN="true"; shift ;;
    -y|--yes)      ASSUME_YES="true"; shift ;;
    --force-unverified) FORCE_UNVERIFIED="true"; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$DATABASE" != "dbAnalytics" && "$DATABASE" != "db" ]]; then
  echo "❌ --database must be 'dbAnalytics' (ClickHouse) or 'db' (MySQL); got '$DATABASE'" >&2
  exit 2
fi

if [[ -z "${OPIK_VERSION:-}" ]]; then
  echo "❌ OPIK_VERSION is not set. Inside the opik-backend container it is set for you;" >&2
  echo "   elsewhere, export it to match the jar you are running against." >&2
  exit 2
fi

JAR="opik-backend-${OPIK_VERSION}.jar"
if [[ ! -f "$JAR" ]]; then
  echo "❌ $JAR not found in $(pwd). Run this from the backend's working directory (/opt/opik)." >&2
  exit 2
fi

if [[ ! -f "$CONFIG" ]]; then
  echo "❌ Config file '$CONFIG' not found in $(pwd)." >&2
  exit 2
fi

# Count the tables in the analytics schema over ClickHouse's HTTP interface, using the same
# connection details the migrations themselves run under. `curl` is in the image for the RDS cert
# bundle, so this adds no dependency; there is no MySQL client, which is why 'db' has no probe.
#
# The migrations URL is not a fixed shape across deployments — the packaged config default carries
# a '/opik' path, helm and compose pass host:port bare, and a managed endpoint adds query
# parameters and may be TLS. The driver's contract is only that 'jdbc:clickhouse:' prefixes the
# rest ("'jdbc:clickhouse:' prefix is mandatory", ClickhouseJdbcUrlParser), so the remainder can be
# either '//host:port' or an embedded-protocol 'https://host:port'. Handle both rather than
# stripping a fixed '//' scheme, which would leave 'jdbc:clickhouse:https:' as the host.
# Echo `-1` on anything unparseable or unreachable, so a probe that cannot answer never reads as
# "0 tables" — the count is a safety gate, and an unknown must never look like an empty schema.
analytics_table_count() {
  local url="${ANALYTICS_DB_MIGRATIONS_URL:-}" scheme rest hostport count
  [[ -z "$url" ]] && { echo "-1"; return; }

  rest="${url#jdbc:}"
  case "$rest" in
    clickhouses:*) scheme="https"; rest="${rest#clickhouses:}" ;;
    clickhouse:*) scheme="http"; rest="${rest#clickhouse:}" ;;
    ch:*) scheme="http"; rest="${rest#ch:}" ;;
    *) echo "-1"; return ;;
  esac

  # After the driver prefix: an explicit protocol wins over the scheme implied above.
  case "$rest" in
    https://*) scheme="https"; rest="${rest#https://}" ;;
    http://*) scheme="http"; rest="${rest#http://}" ;;
    //*) rest="${rest#//}" ;;
    *) echo "-1"; return ;;
  esac

  [[ "$url" == *"ssl=true"* ]] && scheme="https"

  hostport="${rest%%/*}"
  hostport="${hostport%%\?*}"
  [[ -z "$hostport" ]] && { echo "-1"; return; }

  count="$(curl -sS -m 30 \
    -u "${ANALYTICS_DB_MIGRATIONS_USER:-}:${ANALYTICS_DB_MIGRATIONS_PASS:-}" \
    --data-urlencode "query=SELECT count() FROM system.tables WHERE database = {db:String}" \
    --data-urlencode "param_db=${ANALYTICS_DB_DATABASE_NAME:-opik}" \
    --get "${scheme}://${hostport}/" 2>/dev/null)" || { echo "-1"; return; }

  [[ "$count" =~ ^[0-9]+$ ]] || { echo "-1"; return; }
  echo "$count"
}

echo "🔍 Pending changesets for '${DATABASE}' — these are what would be marked as applied:"
echo
java -jar "$JAR" "$DATABASE" status --verbose "$CONFIG"
echo

# Fingerprint of the pending set as reviewed. The operator confirms against what they just read,
# so if the ledger moves under us while the prompt waits — another operator recovering, or a
# deployment applying migrations — we must not silently write a different set than the one shown.
#
# `fast-forward --dry-run` emits the generated changelog-sync SQL, which embeds per-run values
# (timestamps, ordering, Liquibase version banner). Comparing it verbatim would flag a change on
# every capture, so reduce it to the changeset identities — the ID/AUTHOR/FILENAME triples in the
# DATABASECHANGELOG inserts — which are stable for an unchanged pending set.
#
# On a clean ledger this still prints a substantial report (the changelog listing and Liquibase
# banner, ~150 lines); what makes the fingerprint empty is that none of it matches the INSERT
# pattern, not that the command falls silent. Anything that changes the grep must preserve that.
#
# `|| true` is scoped to grep alone: no match is the legitimate "nothing pending" case, and under
# `set -o pipefail` its exit 1 would otherwise abort the script — including right after a
# successful re-baseline, when a clean ledger is exactly what we expect. A failing java or sed
# still propagates. stderr is captured rather than discarded: Liquibase and Dropwizard log to
# stdout, so it is empty in normal operation and holds only a genuine JVM-level failure, which an
# operator mid-recovery needs to see instead of a bare non-zero exit.
pending_fingerprint() {
  local stderr_file
  stderr_file="$(mktemp)"
  if ! java -jar "$JAR" "$DATABASE" fast-forward --all --dry-run "$CONFIG" 2>"$stderr_file" \
    | { grep -oiE "INSERT INTO [^(]*DATABASECHANGELOG[^(]*\\([^)]*\\) VALUES \\('[^']*', *'[^']*', *'[^']*'" || true; } \
    | sed -E "s/.*VALUES *\\('([^']*)', *'([^']*)', *'([^']*)'.*/\\1::\\2::\\3/" \
    | sort; then
    echo "❌ Could not determine the pending changesets for '${DATABASE}'." >&2
    [[ -s "$stderr_file" ]] && cat "$stderr_file" >&2
    rm -f "$stderr_file"
    return 1
  fi
  rm -f "$stderr_file"
}

reviewed_pending="$(pending_fingerprint)"
pending_count="$(printf '%s' "$reviewed_pending" | grep -c . || true)"

tables_before="-1"
if [[ "$DATABASE" == "dbAnalytics" ]]; then
  tables_before="$(analytics_table_count)"
fi

# The precondition — "the schema is already at head" — is the only thing that makes this operation
# safe, and it is the one thing the other checks cannot see. The post-sync clean check is trivially
# satisfied once every changeset is marked applied, so a schema that is not built passes it while
# being stranded permanently. Verify it here, before the prompt, so a refusal costs nothing.
if [[ "$DATABASE" != "dbAnalytics" ]]; then
  if [[ "$FORCE_UNVERIFIED" != "true" ]]; then
    echo "❌ The schema cannot be verified for '${DATABASE}': there is no MySQL client in this image," >&2
    echo "   so the 'is the schema at head' precondition cannot be checked." >&2
    echo "   Re-run with --force-unverified if you have confirmed the schema is at head yourself." >&2
    exit 2
  fi
  echo "⚠️  Proceeding unverified for '${DATABASE}' — the schema-at-head precondition is yours to assert."
elif [[ "$tables_before" -lt 0 ]]; then
  if [[ "$FORCE_UNVERIFIED" != "true" ]]; then
    echo "❌ Could not read the table count from ClickHouse, so the schema cannot be verified as" >&2
    echo "   built. Check ANALYTICS_DB_MIGRATIONS_URL / _USER / _PASS and that the server is" >&2
    echo "   reachable, or re-run with --force-unverified to assert it yourself." >&2
    exit 2
  fi
  echo "⚠️  Proceeding unverified — the ClickHouse table count could not be read."
elif [[ "$pending_count" -gt 0 ]] \
  && (( tables_before * 100 < pending_count * MIN_TABLES_PER_PENDING_CHANGESET_PERCENT )); then
  echo "❌ Refusing to re-baseline: '${ANALYTICS_DB_DATABASE_NAME:-opik}' has only ${tables_before} table(s)," >&2
  echo "   against ${pending_count} pending changeset(s). The schema is not built, so this is not a lost" >&2
  echo "   ledger over an intact schema — it is an empty database." >&2
  echo >&2
  echo "   Re-baselining here would mark every migration applied without running it, and the" >&2
  echo "   deployment could then never build its schema. Nothing was written." >&2
  echo >&2
  echo "   If the schema really is at head and this count is wrong, re-run with --force-unverified." >&2
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "ℹ️  Dry run — nothing was changed."
  echo "   Re-run without --dry-run to record the changesets above as applied."
  exit 0
fi

cat <<EOF
⚠️  About to mark every pending changeset above as APPLIED for '${DATABASE}', without running it.

    Do this only if the schema is already at the changelog's head. If any changeset above has
    NOT actually been applied to this database, it will be recorded as done and never run —
    leaving the schema permanently behind a ledger that claims otherwise.

    No DDL is executed and no data is modified; only ledger rows are written.
EOF
echo

if [[ "$ASSUME_YES" != "true" ]]; then
  read -r -p "Type 'yes' to continue: " reply
  if [[ "$reply" != "yes" ]]; then
    echo "Aborted — nothing was changed."
    exit 1
  fi
fi

current_pending="$(pending_fingerprint)"
if [[ "$current_pending" != "$reviewed_pending" ]]; then
  echo
  echo "❌ The pending changesets changed while this was waiting — the ledger was modified by" >&2
  echo "   something else (another recovery, or a deployment applying migrations)." >&2
  echo "   Nothing was written. Re-run to review the current set before confirming." >&2
  exit 1
fi

echo
echo "📌 Re-baselining '${DATABASE}' changelog..."
java -jar "$JAR" "$DATABASE" fast-forward --all "$CONFIG"

echo
echo "🔍 Verifying '${DATABASE}' has nothing left pending..."
java -jar "$JAR" "$DATABASE" status --verbose "$CONFIG"

# changelogSync writes ledger rows only, so the schema must come out the other side identical. A
# changed table count means something executed DDL — assert it rather than trusting the contract.
#
# A negative count is the probe failing, not a schema that shrank, and the two need different
# messages: the ledger has already been written by this point, so telling an operator the schema
# changed would send them hunting for DDL that never ran. Only compare two real counts.
if [[ "$tables_before" -ge 0 ]]; then
  tables_after="$(analytics_table_count)"
  if [[ "$tables_after" -lt 0 ]]; then
    echo
    echo "⚠️  Could not re-read the table count after the sync, so the schema could not be confirmed" >&2
    echo "   unchanged. The ledger rows have already been written — this is not a failure of the" >&2
    echo "   re-baseline itself. Verify the schema before starting any replica against it." >&2
  elif [[ "$tables_after" != "$tables_before" ]]; then
    echo >&2
    echo "❌ The table count changed across the re-baseline (${tables_before} → ${tables_after})." >&2
    echo "   changelogSync writes ledger rows only, so the schema should be untouched. Inspect the" >&2
    echo "   database before starting any replica against it." >&2
    exit 1
  fi
fi

# `status` reports but never fails — it exits 0 whether or not changesets are pending, so it
# cannot gate on its own. Reuse the identity fingerprint: it is empty exactly when no changeset
# remains pending, and unlike the raw SQL it is not perturbed by banners or timestamps.
remaining="$(pending_fingerprint)"

if [[ -n "$remaining" ]]; then
  echo
  echo "❌ Re-baseline did not complete: '${DATABASE}' still reports pending changesets." >&2
  echo "   The ledger is not clean, and a restarting replica would still try to replay them." >&2
  echo "   Re-run with --dry-run to inspect what remains before retrying." >&2
  exit 1
fi

echo
echo "✅ Re-baseline complete — no changesets remain pending. A restarting replica will now skip"
echo "   them instead of replaying them against the existing schema."
