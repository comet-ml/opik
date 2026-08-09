#!/usr/bin/env bash
# Fails if any ClickHouse analytics migration contains ADD COLUMN without IF NOT EXISTS.
# All migrations from 000004 use IF NOT EXISTS; this script enforces the pattern for all files.
# The check is case-insensitive and whitespace-tolerant so variants like
# "add column" or "ADD  COLUMN" (extra space) are also caught.
set -euo pipefail

MIGRATIONS_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"

violations=""
while IFS= read -r line; do
    # line format from grep -n: <file>:<lineno>:<content>
    # Extract the content portion (everything after the second colon).
    content="${line#*:}"    # strip file prefix
    content="${content#*:}" # strip line-number prefix
    # Skip SQL comment lines (optional whitespace then --)
    if [[ "$content" =~ ^[[:space:]]*-- ]]; then
        continue
    fi
    # Case-insensitive check: skip if IF NOT EXISTS is present
    # (grep -i already matched ADD COLUMN case-insensitively above;
    #  now check IF NOT EXISTS case-insensitively)
    lower=$(echo "$content" | tr '[:upper:]' '[:lower:]')
    if [[ "$lower" == *"if not exists"* ]]; then
        continue
    fi
    violations="${violations}${line}"$'\n'
done < <(grep -rni "add[[:space:]]\+column[[:space:]]\+" "$MIGRATIONS_DIR" || true)

if [ -n "$violations" ]; then
    echo "ERROR: Found ADD COLUMN without IF NOT EXISTS in analytics migrations:"
    printf '%s' "$violations"
    echo ""
    echo "All ADD COLUMN statements must use IF NOT EXISTS to be idempotent."
    echo "Replace: ADD COLUMN <name>"
    echo "   With: ADD COLUMN IF NOT EXISTS <name>"
    exit 1
fi

echo "OK: All ADD COLUMN statements in analytics migrations use IF NOT EXISTS."
