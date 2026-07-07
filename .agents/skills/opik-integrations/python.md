# Python Integration Anatomy

Reference for building integrations under `sdks/python/src/opik/integrations/<name>/`. Read this alongside the `python-sdk` skill (architecture, batching, testing fixtures).

## Directory skeleton

```
sdks/python/src/opik/integrations/<name>/
├── __init__.py                 # exports the public entrypoint, nothing else
├── opik_tracker.py             # the entrypoint: track_<name>() (patching) or the tracer class
├── <name>_decorator.py         # BaseTrackDecorator subclass(es) — patching integrations
└── <name>_chunks_aggregator.py # merges streamed chunks into one response — if streaming
```

Callback integrations (LangChain-style) replace the decorator file with an `opik_tracer.py` holding a `BaseTracer` subclass plus helpers (`run_parse_helpers.py`, provider usage extractors). Hybrid integrations (ADK) add a `patchers/` package and callback injectors.

## Mechanism templates — clone, don't invent

| Mechanism | When | Canonical template |
|---|---|---|
| **Method patching** | SDK client with methods to wrap (most providers) | `integrations/openai/` |
| **Pure callback** | framework exposes a callback/tracer interface | `integrations/langchain/` |
| **Hybrid** | callbacks unreliable / also need method hooks | `integrations/adk/` |
| **OTel** | framework already emits OpenTelemetry spans | `integrations/otel/` |

### Method patching (the common case)

The entrypoint mutates the client in place and returns it. Study `openai/opik_tracker.py` — the shape is:

```python
def track_<name>(client, project_name=None, provider=None):
    if hasattr(client, "opik_tracked"):   # idempotency guard
        return client
    client.opik_tracked = True
    # resolve provider, then patch each method via a decorator factory
    _patch_<name>(client, provider, project_name)
    return client
```

Each method is wrapped by a `BaseTrackDecorator` subclass (`<name>_decorator.py`). You implement two preprocessors; everything else (span/trace creation, context nesting, error capture, generator handling) comes from the base class. Read `openai/openai_chat_completions_decorator.py` as the template:

- `_start_span_inputs_preprocessor(...)` → returns `StartSpanParameters` (type, name, input, metadata, tags, model, provider).
- `_end_span_inputs_preprocessor(...)` → returns `EndSpanParameters` (output, usage, model, provider, metadata).
- Streaming: pass a `generations_aggregator` to `.track(...)` and override the stream handler so chunks are accumulated and the span finalizes after iteration. Sync iterators, async iterators, and stream context managers each need patching — see `openai/stream_patchers.py`.

Wrapped calls check `opik.is_tracing_active()` at call time and no-op the telemetry if tracing is off, while still running the underlying call.

**Delegating methods (patch the primitive, name from kwargs).** When a higher-level method calls a lower-level one you patch (Mistral's `chat.parse` → `chat.complete`, `parse_stream` → `stream`; openai's older `beta…stream` → `create`), do **not** patch both — that produces two spans and double-counts cost. Patch **only the primitive**, and distinguish the calling mode from a kwarg the delegating method forwards. openai names the stream span via `if kwargs.get("stream") is True: name = "chat_completion_stream"`; the Mistral integration names the parse span via `if kwargs.get("response_format") is not None: name = "chat_completion_parse"` inside `_start_span_inputs_preprocessor`. This uses only the existing `track()` API — no reentrancy flags, no `contextvars`, no `set_tracing_active` (which is process-wide and racy). Trade-off: the span carries the primitive's raw response (e.g. no deserialized `.parsed` field, though the structured JSON is in the output content). Verify in Phase 5 that the delegating call yields a single correctly-named span with un-doubled cost. Only if a kwarg can't distinguish the modes should you consider a heavier mechanism.

**Env var mismatch.** A provider SDK's client may not auto-read its own API-key env var (e.g. `mistralai.Mistral()` ignores `MISTRAL_API_KEY`). In tests and examples, pass the key explicitly — `Mistral(api_key=os.environ["MISTRAL_API_KEY"])` — rather than relying on the bare constructor.

### OpenTelemetry (backend-first)

When the target already emits OpenTelemetry, most of the work lives on the **backend**: Opik exposes an OTLP ingestion endpoint that maps OTel spans to Opik traces. Many OTel "integrations" are therefore *docs-only* — the user points their framework's OTLP exporter at Opik with auth headers and writes no SDK code. Always check whether that covers the need before writing code.

Add client-side code only when you must shape what the backend receives (set Opik semantics, remap attributes, bridge a framework that won't emit raw OTLP). The building block is `integrations/otel/`:

- `OpikSpanProcessor` — an OTel `SpanProcessor` the user registers on their `TracerProvider` to forward/annotate spans.
- distributed-trace helpers — `attach_to_parent`, `extract_opik_distributed_trace_attributes`.

A framework-specific OTel **tracer wrapper** (see `adk/patchers/adk_otel_tracer/`) is the heavier variant, for intercepting a framework's own tracer rather than a generic processor.

## Shared core — never re-derive these

| Concern | Module |
|---|---|
| Decorator base class | `opik/decorator/base_track_decorator.py` |
| Span/trace creation respecting context | `opik/decorator/span_creation_handler.py` |
| Start/End span dataclasses | `opik/decorator/arguments_helpers.py` |
| Error capture (`exception_type`, `traceback`) | `opik/decorator/error_info_collector.py` |
| Generator/stream wrapping | `opik/decorator/generator_wrappers.py` |
| Token usage normalization | `opik/llm_usage.py` → `try_build_opik_usage_or_log_error(provider=..., usage=...)` |
| Recognized providers (cost tracking) | `opik/types.py` → `LLMProvider` |
| Global client | `opik/api_objects/opik_client.py` → `get_global_client()` |
| Context stack | `opik/context_storage.py` |

For callback integrations, span/trace creation goes through `span_creation_handler` too; map each framework run id → `SpanData`/`TraceData` and set `metadata["created_from"] = "<name>"`.

## Dependencies & imports

- The framework library is **imported inside the integration module** (`import mistralai` at the top of `opik_tracker.py`). That is safe because the module is only reached when a user does `from opik.integrations.<name> import ...`. Never import an integration from the `opik` package top level.
- Do **not** add the framework to `install_requires` unless it is already a core dependency (openai and litellm are; most are not). Users install the framework themselves.
- `integrations/<name>/__init__.py` exports only the public entrypoint.
- If the integration must support multiple incompatible framework versions, branch at import time on `opik.semantic_version` (see `adk/__init__.py`).

## Tests

Location: `sdks/python/tests/library_integration/<name>/`. Follow the `python-sdk` testing skill for fixtures; the integration-specific shape:

```python
def test_<name>_<method>__happyflow(fake_backend):
    client = track_<name>(<lib>.Client())
    response = client.<method>(...)          # real call, gated by env fixture
    opik.flush_tracker()

    EXPECTED = TraceModel(
        id=ANY_BUT_NONE, name="...", input=ANY_DICT.containing({...}),
        output=ANY_BUT_NONE, tags=["<name>"], spans=[
            SpanModel(
                id=ANY_BUT_NONE, type="llm", name="...",
                usage=..., model=ANY_STRING.starting_with("..."),
                provider="<name>", spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED, fake_backend.trace_trees[0])
```

- Real API calls are gated by an `ensure_<name>_configured` fixture (skip if the key is missing) — add one to `conftest.py` mirroring `ensure_openai_configured`.
- Cover: happy flow, streaming, `parse`/structured-output (+ its single-span/no-double-cost assertion), custom `provider`, nested-under-`@track`, and an error case.
- Centralize the model id in `tests/llm_constants.py`; add a `requirements.txt` in the test dir with the framework package.
- Imports: `from tests.testlib import TraceModel, SpanModel, ANY_BUT_NONE, ANY_DICT, ANY_STRING, assert_equal`.
- **Wire the tests into CI** — create `.github/workflows/lib-<name>-tests.yml` (single Python version unless asked otherwise) and register it in `lib-integration-tests-runner.yml`. See Phase 6 of [workflow.md](workflow.md); unregistered tests never run.
