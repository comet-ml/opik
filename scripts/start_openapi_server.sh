#!/bin/bash
set -e

REDOC_RELATIVE_PATH="apps/opik-backend/redoc"

# Generate openapi.yaml
cd apps/opik-backend
mvn clean compile swagger:resolve
cd -

# Copy openapi.yaml for Redoc
cp apps/opik-backend/target/openapi.yaml $REDOC_RELATIVE_PATH

# Resolve the absolute path of the Redoc directory, as jwebserver doesn't work with relative paths
cd $REDOC_RELATIVE_PATH
REDOC_ABSOLUTE_PATH=$(pwd)
cd -

# Start the Redoc server
jwebserver -d "$REDOC_ABSOLUTE_PATH" -b 0.0.0.0 -p 3003
