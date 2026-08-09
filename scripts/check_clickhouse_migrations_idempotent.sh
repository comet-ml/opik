#!/usr/bin/env bash
# Fails if any ClickHouse analytics migration contains ADD COLUMN without IF NOT EXISTS.
# All migrations from 000004 use IF NOT EXISTS; this script enforces the pattern for all files.
set -euo pipefail

MIGRATIONS_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"

# Extract every ADD COLUMN clause that lacks IF NOT EXISTS.
# We parse line-by-line from grep -n output (format: "file:linenum:content").
# Comment lines (content starting with --) are excluded by matching only lines
# whose content portion does not start with --.
violations=""
while IFS= read -r line; do
    # line format: <file>:<lineno>:<content>
    # Extract content (everything after the second colon)
    content="${line#*:*:}"
    # Skip SQL comment lines (content starts with optional whitespace then --)
    if [[ "$content" =~ ^[[:space:]]*-- ]]; then
        continue
    fi
    violations="${violations}${line}"$'\n'
done < <(grep -rn "ADD COLUMN " "$MIGRATIONS_DIR" | grep -v "IF NOT EXISTS" || true)

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
