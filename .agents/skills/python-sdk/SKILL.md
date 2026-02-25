---
name: python-sdk
description: Python SDK patterns for Opik. Use when working in sdks/python, on SDK APIs, integrations, or message processing.
---

# Python SDK

## Three-Layer Architecture
```
Layer 1: Public API      (opik.Opik, @opik.track)
    ↓
Layer 2: Message Processing   (queue, batching, retry)
    ↓
Layer 3: REST Client     (OpikApi, HTTP)
```

## Critical Gotchas

### Flush Before Exit
```python
# ✅ REQUIRED for async operations
client = opik.Opik()
# ... tracing operations ...
client.flush()  # Must call before exit!
```

### Async vs Sync Operations

**Async (via message queue)** - fire-and-forget:
- `trace()`, `span()`
- `log_traces_feedback_scores()`
- `experiment.insert()`

**Sync (blocking, returns data)**:
- `create_dataset()`, `get_dataset()`
- `create_prompt()`, `get_prompt()`
- `search_traces()`, `search_spans()`

### Lazy Imports for Integrations
```python
# ✅ GOOD - integration files assume dependency exists
import anthropic  # Only imported when user uses integration

# ❌ BAD - importing at package level
from opik.integrations import anthropic  # Would fail if not installed
```

## Integration Patterns

### Pattern Selection
```
Library has callbacks? → Pure Callback (LangChain, LlamaIndex)
No callbacks?         → Method Patching (OpenAI, Anthropic)
Callbacks unreliable? → Hybrid (ADK)
```

### Method Patching (OpenAI, Anthropic)
```python
from opik.integrations.anthropic import track_anthropic

client = anthropic.Anthropic()
tracked_client = track_anthropic(client)  # Wraps methods
```

### Callback-Based (LangChain)
```python
from opik.integrations.langchain import OpikTracer

tracer = OpikTracer()
chain.invoke(input, config={"callbacks": [tracer]})
```

### Decorator-Based
```python
@opik.track
def my_function(input: str) -> str:
    # Auto-creates span, captures input/output
    return process(input)
```

## Dependency Policy
- Avoid adding new dependencies
- Use conditional imports for integrations
- Keep version bounds flexible: `>=2.0.0,<3.0.0`

## Batching System
Messages batch together for efficiency:
- Flush triggers: time (1s), size (100), memory (50MB), manual
- Reduces HTTP overhead significantly

## API Method Naming
```python
# CRUD: create/get/list/update/delete
client.create_experiment(name="exp")
client.get_dataset(name="ds")

# Search for complex queries
client.search_spans(project_name="proj")
client.search_traces(project_name="proj")

# Batch for bulk operations
client.batch_create_items(...)
```

## Reference Files
- [testing.md](testing.md) - fake_backend, verifiers, test naming
- [error-handling.md](error-handling.md) - Exception hierarchy, MetricComputationError
- [good-code.md](good-code.md) - Access control, imports, factories, DI
