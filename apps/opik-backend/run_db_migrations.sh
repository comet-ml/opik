#!/bin/bash
set -e

echo "Running database migrations..."
echo "Current directory is: $(pwd)"
echo "OPIK_VERSION=$OPIK_VERSION"

# The two migrations are independent: run both even if the first fails, then fail if either did.
# `a && b` under `set -e` skips b when a fails and the script still falls through to the success
# message and exits 0, so the init container passes against databases that were not migrated.
state_status=0
analytics_status=0
java -jar opik-backend-"$OPIK_VERSION".jar db migrate config.yml || state_status=$?
java -jar opik-backend-"$OPIK_VERSION".jar dbAnalytics migrate config.yml || analytics_status=$?

if [ "$state_status" -ne 0 ] || [ "$analytics_status" -ne 0 ]; then
  echo "Database migrations failed (db migrate: exit $state_status, dbAnalytics migrate: exit $analytics_status)" >&2
  exit 1
fi

echo "Database migrations completed successfully"
