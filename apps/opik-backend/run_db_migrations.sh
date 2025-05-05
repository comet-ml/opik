#!/bin/bash
set -e

echo "Running database migrations..."
echo "Current directory is: $(pwd)"
echo "OPIK_VERSION=$OPIK_VERSION"

java -jar opik-backend-"$OPIK_VERSION".jar db migrate config.yml \
  && java -Dliquibase.clickhouse.configfile=liquibaseClickhouse.conf -jar opik-backend-"$OPIK_VERSION".jar dbAnalytics migrate config.yml

echo "Database migrations completed successfully"
