#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

git diff --check
cd apps/opik-backend
mvn -Dtest=OpenTelemetryMapperTest test

echo "validation ok"
