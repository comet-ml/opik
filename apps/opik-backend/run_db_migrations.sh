#!/bin/bash

echo "Running database migrations..."
echo "Current directory is: $(pwd)"
echo "OPIK_VERSION=$OPIK_VERSION"

java -jar opik-backend-"$OPIK_VERSION".jar db migrate config.yml
DB_EXIT=$?

java -jar opik-backend-"$OPIK_VERSION".jar dbAnalytics migrate config.yml
ANALYTICS_EXIT=$?

if [ $DB_EXIT -ne 0 ] || [ $ANALYTICS_EXIT -ne 0 ]; then
  echo "Database migrations FAILED (db=$DB_EXIT, analytics=$ANALYTICS_EXIT)"
  exit 1
fi

echo "Database migrations completed successfully"
