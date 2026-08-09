#!/usr/bin/env bash
# Fails if any ClickHouse analytics migration contains ADD COLUMN without IF NOT EXISTS.
# All migrations from 000004 use IF NOT EXISTS; this script enforces the pattern for all files.
set -euo pipefail

MIGRATIONS_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"

# Find ADD COLUMN lines that lack IF NOT EXISTS, excluding comment lines (starting with --)
# and DROP COLUMN lines in rollback comments
violations=$(grep -rn "ADD COLUMN " "$MIGRATIONS_DIR" \
  | grep -v "IF NOT EXISTS" \
  | grep -v "^[^:]*:--" \
  | grep -v "DROP COLUMN") || true

if [ -n "$violations" ]; then
  echo "ERROR: Found ADD COLUMN without IF NOT EXISTS in analytics migrations:"
  echo "$violations"
  echo ""
  echo "All ADD COLUMN statements must use IF NOT EXISTS to be idempotent."
  echo "Replace: ADD COLUMN <name>"
  echo "   With: ADD COLUMN IF NOT EXISTS <name>"
  exit 1
fi

echo "OK: All ADD COLUMN statements in analytics migrations use IF NOT EXISTS."
