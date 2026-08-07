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
# missing migrations are marked applied and silently never run. Always inspect the status output
# and confirm the schema is at head before answering yes.

set -euo pipefail

DATABASE="dbAnalytics"
ASSUME_YES="false"
DRY_RUN="false"
CONFIG="config.yml"

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
  -y, --yes               Skip the confirmation prompt. For non-interactive runs only —
                          you are asserting the schema is already at head.
  -h, --help              Show this help.

Examples:
  # Inspect what is pending, change nothing
  ./rebaseline_db_changelog.sh --dry-run

  # Re-baseline the ClickHouse ledger after confirming the schema is at head
  ./rebaseline_db_changelog.sh

  # Re-baseline the MySQL ledger
  ./rebaseline_db_changelog.sh --database db
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--database) DATABASE="${2:-}"; shift 2 ;;
    -c|--config)   CONFIG="${2:-}"; shift 2 ;;
    -n|--dry-run)  DRY_RUN="true"; shift ;;
    -y|--yes)      ASSUME_YES="true"; shift ;;
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

echo "🔍 Pending changesets for '${DATABASE}' — these are what would be marked as applied:"
echo
java -jar "$JAR" "$DATABASE" status --verbose "$CONFIG"
echo

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

echo
echo "📌 Re-baselining '${DATABASE}' changelog..."
java -jar "$JAR" "$DATABASE" fast-forward --all "$CONFIG"

echo
echo "🔍 Verifying '${DATABASE}' has nothing left pending..."
java -jar "$JAR" "$DATABASE" status --verbose "$CONFIG"

# `status` reports but never fails — it exits 0 whether or not changesets are pending, so it
# cannot gate on its own. `fast-forward --dry-run` emits the DDL it *would* mark next and prints
# nothing when the ledger is clean, which gives a signal that doesn't depend on parsing prose.
remaining="$(java -jar "$JAR" "$DATABASE" fast-forward --all --dry-run "$CONFIG" 2>/dev/null | tr -d '[:space:]')"

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
