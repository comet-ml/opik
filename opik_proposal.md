# AG2 Native Integration — Proposal

## Approach

Create a native `opik.integrations.ag2` package that leverages AG2's built-in OTel instrumentor and bridges OTel spans into Opik's trace/span system via a custom `SpanProcessor`.

### Architecture

```
AG2 Agent  →  autogen.opentelemetry  →  OTel Spans
                                            ↓
                                   OpikSpanProcessor
                                            ↓
                                   Opik Traces & Spans
```

### User-Facing API

```python
from opik.integrations.ag2 import OpikInstrumentor

instrumentor = OpikInstrumentor(project_name="my-project")
instrumentor.instrument_agent(agent1)
instrumentor.instrument_agent(agent2)
# LLM calls are automatically instrumented at init time
```

### Key Components

1. **`OpikInstrumentor`** — Main class. Creates an OTel `TracerProvider` with `OpikSpanProcessor`, calls `instrument_llm_wrapper()` at init, exposes `instrument_agent()` and `instrument_pattern()`.

2. **`OpikSpanProcessor`** — OTel `SpanProcessor` implementation. On span start: creates Opik `TraceData` (root) or `SpanData` (child). On span end: maps AG2 attributes to Opik fields and finalizes.

### Attribute Mapping

| AG2 OTel Attribute | Opik Field |
|---|---|
| `ag2.span.type` | span `type` (llm→llm, tool→tool, etc.) |
| `gen_ai.request.model` / `gen_ai.response.model` | `model` |
| `gen_ai.usage.input_tokens` | `usage.prompt_tokens` |
| `gen_ai.usage.output_tokens` | `usage.completion_tokens` |
| `gen_ai.provider.name` | `provider` |
| `gen_ai.input.messages` | span `input` |
| `gen_ai.output.messages` | span `output` |
| `gen_ai.agent.name` | span `name` |

## Deliverables

1. SDK integration: `sdks/python/src/opik/integrations/ag2/`
2. Frontend integration script and card update
3. Documentation rewrite (remove OTel boilerplate, show native usage)
4. Integration tests using `fake_backend` fixture
5. Optimizer agent example
