#!/bin/bash

set -e

echo "🔍 Checking for migration file prefix conflicts..."

# Define migration directories
DB_STATE_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-state/migrations"
DB_ANALYTICS_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"

# Function to get migration files from main branch
get_main_branch_migrations() {
  local dir=$1
  git ls-tree -r --name-only origin/main -- "$dir" | grep '\.sql$' || true
}

# Function to extract prefixes from file list
extract_prefixes_from_list() {
  local file_list=$1
  echo "$file_list" | sed 's/.*\/\([0-9]\{6\}\)_.*/\1/' | sort
}

# Function to get new migration files in PR
get_new_migrations() {
  local dir=$1
  git diff --name-only origin/main...HEAD -- "$dir" | grep '\.sql$' || true
}

# Function to extract prefix from filename
get_prefix() {
  local filename=$1
  basename "$filename" | sed 's/^\([0-9]\{6\}\)_.*/\1/'
}

# Function to check migrations for a specific directory
check_migrations() {
  local dir=$1
  local dir_name=$2

  echo "📁 Checking $dir_name migrations..."

  # Get existing migration files in main branch
  MAIN_MIGRATION_FILES=$(get_main_branch_migrations "$dir")
  MAIN_PREFIXES=""
  if [ -n "$MAIN_MIGRATION_FILES" ]; then
    MAIN_PREFIXES=$(extract_prefixes_from_list "$MAIN_MIGRATION_FILES")
  fi

  # Get new migration files in this PR
  NEW_MIGRATIONS=$(get_new_migrations "$dir")

  # Early return if no new migrations found
  if [ -z "$NEW_MIGRATIONS" ]; then
    echo "ℹ️  No new $dir_name migration files found"
    return 0
  fi

  echo "🆕 New migration files in $dir_name:"
  echo "$NEW_MIGRATIONS" | while read -r file; do
    echo "  - $file"
  done
  
  # Debug: Show main branch prefixes
  if [ -n "$MAIN_PREFIXES" ]; then
    echo "🔍 Existing prefixes in main branch: $(echo "$MAIN_PREFIXES" | tr '\n' ' ')"
  else
    echo "🔍 No existing migration files found in main branch"
  fi
  
  # Check each new migration for prefix conflicts
  CONFLICT_FOUND=false
  while IFS= read -r file; do
    if [ -n "$file" ]; then
      PREFIX=$(get_prefix "$file")
      if [ -n "$MAIN_PREFIXES" ] && echo "$MAIN_PREFIXES" | grep -q "^$PREFIX$"; then
        echo "❌ CONFLICT DETECTED: Migration prefix '$PREFIX' from file '$file' already exists in main branch"
        echo "   Please update the migration file prefix to a number not yet used in main."
        CONFLICT_FOUND=true
      else
        echo "✅ Migration prefix '$PREFIX' is available"
      fi
    fi
  done <<< "$NEW_MIGRATIONS"
  
  if [ "$CONFLICT_FOUND" = true ]; then
    return 1
  fi

  return 0
}

# Check both migration directories
OVERALL_SUCCESS=true

if ! check_migrations "$DB_STATE_DIR" "db-app-state"; then
  OVERALL_SUCCESS=false
fi

echo ""

if ! check_migrations "$DB_ANALYTICS_DIR" "db-app-analytics"; then
  OVERALL_SUCCESS=false
fi

if [ "$OVERALL_SUCCESS" = false ]; then
  echo ""
  echo "💥 Migration prefix conflicts detected! Please resolve conflicts before merging."
  echo ""
  echo "ℹ️  To fix this issue:"
  echo "   1. Check the highest prefix number used in main branch for the respective database"
  echo "   2. Update your migration file prefix to a number not yet used in main."
  echo "   3. Commit and push the changes"
  exit 1
fi

echo ""
echo "🎉 All migration prefixes are unique - no conflicts detected!"