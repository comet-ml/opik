#!/bin/bash
set -e

OPENAPI_YML_PATH="apps/opik-backend/target/openapi.yaml"

# Generate openapi.yaml
cd apps/opik-backend
mvn compile swagger:resolve
cd -

# Copy openapi.yaml for Fern generation
cp $OPENAPI_YML_PATH sdks/code_generation/fern/openapi/

# Copy openapi.yaml for the documentation
cp $OPENAPI_YML_PATH apps/opik-documentation/documentation/rest_api/opik.yaml

# Generate SDKs with Fern from copied openapi.yaml
cd sdks/code_generation
fern generate
cd -
