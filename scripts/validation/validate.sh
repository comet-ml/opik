#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

git diff --check
test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/openinference.mdx
test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/openllmetry.mdx
rg -n 'title="OpenInference" href="/docs/opik/integrations/openinference"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
rg -n 'title="OpenLLMetry" href="/docs/opik/integrations/openllmetry"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx

echo "validation ok"
