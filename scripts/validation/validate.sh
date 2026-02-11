#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

git diff --check
cd apps/opik-backend
mvn -DskipTests test
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  mvn -Dtest=OpenTelemetryResourceTest#testScopedPlatformFixtures test
else
  echo "Docker not available; skipped testcontainers fixture run"
fi

echo "validation ok"
