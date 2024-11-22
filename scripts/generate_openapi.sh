#!/bin/bash
set -e

OPENAPI_YML_PATH="apps/opik-backend/target/openapi.yaml"

# Generate openapi.yaml
cd apps/opik-backend
mvn compile swagger:resolve
cd -

# Copy openapi.yaml for Fern generation
cp $OPENAPI_YML_PATH sdks/python/code_generation/fern/openapi/

# Generate Python SDK with Fern from copied openapi.yaml
cd sdks/python/code_generation
fern generate
cd -

# Format Python code
cd sdks/python
pre-commit run --all-files
cd -
