#!/bin/bash
set -e

OPENAPI_YML_PATH="apps/opik-backend/target/openapi.yaml"

# Generate openapi.yaml
cd apps/opik-backend
mvn compile swagger:resolve
cd -

# Copy openapi.yaml for Redoc
cp $OPENAPI_YML_PATH apps/opik-backend/redoc

# Resolve the full path of the Redoc directory, as jwebserver only works with full paths
cd apps/opik-backend/redoc
REDOC_PATH=$(pwd)
cd -

# Start the Redoc server
jwebserver -d "$REDOC_PATH" -b 0.0.0.0 -p 3003
