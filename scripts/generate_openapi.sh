#!/bin/bash
set -e

OPENAPI_YML_PATH="apps/opik-backend/target/openapi.yaml"

# Check if fern is installed
if ! command -v "fern" &> /dev/null; then
    echo "fern is not installed."
    echo "Please follow the instructions: https://github.com/comet-ml/opik/blob/main/sdks/code_generation/fern/README.md"
    exit 1
fi

# Generate openapi.yaml
cd apps/opik-backend
mvn clean compile swagger:resolve
cd -

# Copy openapi.yaml for Fern generation
cp $OPENAPI_YML_PATH sdks/code_generation/fern/openapi/

# Copy openapi.yaml for the documentation
cp $OPENAPI_YML_PATH apps/opik-documentation/documentation/fern/openapi/opik.yaml

# Generate SDKs with Fern from copied openapi.yaml
cd sdks/code_generation
fern generate
cd -
