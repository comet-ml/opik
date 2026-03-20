# AG2 Native Integration — Opportunity

## Problem

Opik currently supports AG2 only through verbose OpenTelemetry boilerplate that requires users to manually configure `TracerProvider`, `BatchSpanProcessor`, `OTLPSpanExporter`, and multiple OpenTelemetry instrumentation packages. This creates a high barrier to entry compared to competing frameworks like CrewAI and ADK which have native Python SDK integrations with Opik.

## Opportunity

AG2 ships its own OpenTelemetry instrumentation at `autogen.opentelemetry` with functions like `instrument_agent()`, `instrument_llm_wrapper()`, and `instrument_pattern()`. These generate rich OTel spans with attributes covering agent conversations, LLM calls, tool usage, code execution, and group chat orchestration.

By creating a native Opik SDK integration that wraps AG2's OTel instrumentor with a custom `SpanProcessor`, we can:

1. **Reduce setup from ~30 lines of OTel boilerplate to 3 lines** — matching the DX of CrewAI and ADK integrations.
2. **Preserve full trace hierarchy** — conversations → agent replies → LLM calls → tool executions.
3. **Map AG2's rich attributes** (model, tokens, provider, messages) directly to Opik's data model.
4. **Lower the install surface** — no more `opentelemetry-sdk`, `opentelemetry-instrumentation-openai`, `opentelemetry-exporter-otlp`, or `opentelemetry-instrumentation-threading` as separate user-managed dependencies.

## Impact

- Improved developer experience for AG2 users adopting Opik
- Parity with CrewAI / ADK integration quality
- Positions Opik as the default observability tool for AG2 multi-agent workflows
