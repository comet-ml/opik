#!/usr/bin/env bash
# Fails if any ClickHouse analytics migration contains ADD COLUMN without IF NOT EXISTS.
# All migrations from 000004 use IF NOT EXISTS; this script enforces the pattern for all files.
# The check is case-insensitive and whitespace-tolerant so variants like
# "add column" or "ADD  COLUMN" (extra space) are also caught.
set -euo pipefail

MIGRATIONS_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"

# Validate the migrations directory exists and is readable before scanning.
if [ ! -d "$MIGRATIONS_DIR" ]; then
    echo "ERROR: Migrations directory not found: $MIGRATIONS_DIR"
    exit 2
fi

violations=""

# Run grep; exit codes: 0 = matches found, 1 = no matches, 2+ = error.
grep_output=$(grep -rni "add[[:space:]]\+column[[:space:]]\+" "$MIGRATIONS_DIR" 2>&1)
grep_rc=$?

if [ "$grep_rc" -eq 2 ]; then
    echo "ERROR: grep failed while scanning $MIGRATIONS_DIR:"
    echo "$grep_output"
    exit 2
fi

# grep_rc 0 = matches found, 1 = no matches (no ADD COLUMN at all — trivially OK).
if [ "$grep_rc" -eq 1 ]; then
    echo "OK: All ADD COLUMN statements in analytics migrations use IF NOT EXISTS."
    exit 0
fi

while IFS= read -r line; do
    # line format from grep -n: <file>:<lineno>:<content>
    # Extract the content portion (everything after the second colon).
    content="${line#*:}"    # strip file prefix
    content="${content#*:}" # strip line-number prefix

    # Skip SQL single-line comment lines (optional whitespace then --)
    if [[ "$content" =~ ^[[:space:]]*-- ]]; then
        continue
    fi

    # Skip SQL block comment lines: lines that start with /* or * (continuation) or */
    if [[ "$content" =~ ^[[:space:]]*/\* ]] || \
       [[ "$content" =~ ^[[:space:]]*\*[^/] ]] || \
       [[ "$content" =~ ^[[:space:]]*\*/ ]]; then
        continue
    fi

    # Case-insensitive check: skip if IF NOT EXISTS is present on this line
    lower=$(echo "$content" | tr '[:upper:]' '[:lower:]')
    if [[ "$lower" == *"if not exists"* ]]; then
        continue
    fi

    violations="${violations}${line}"$'\n'
done <<< "$grep_output"

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
