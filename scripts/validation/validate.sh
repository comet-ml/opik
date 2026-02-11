#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

git diff --check

case "$(git rev-parse --abbrev-ref HEAD)" in
  vincentkoc-code/otel-docs-temporal)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/temporal.mdx
    rg -n 'title="Temporal" href="/docs/opik/integrations/temporal"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  vincentkoc-code/otel-docs-langserve)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/langserve.mdx
    rg -n 'title="LangServe" href="/docs/opik/integrations/langserve"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  vincentkoc-code/otel-docs-quarkus-langchain4j)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/quarkus-langchain4j.mdx
    rg -n 'title="Quarkus LangChain4j" href="/docs/opik/integrations/quarkus-langchain4j"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  vincentkoc-code/otel-docs-claude-agent-sdk)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/claude-agent-sdk.mdx
    rg -n 'title="Claude Agent SDK" href="/docs/opik/integrations/claude-agent-sdk"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  vincentkoc-code/otel-docs-prompt-flow)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/prompt-flow.mdx
    rg -n 'title="Prompt Flow" href="/docs/opik/integrations/prompt-flow"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  vincentkoc-code/otel-docs-rubyllm)
    test -f apps/opik-documentation/documentation/fern/docs/tracing/integrations/rubyllm.mdx
    rg -n 'title="RubyLLM" href="/docs/opik/integrations/rubyllm"' apps/opik-documentation/documentation/fern/docs/tracing/integrations/overview.mdx
    ;;
  *)
    echo "No branch-specific checks configured"
    ;;
esac

echo "validation ok"
