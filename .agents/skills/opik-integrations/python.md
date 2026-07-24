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

**Delegating methods (patch only the primitive).** When a higher-level method calls a lower-level one you patch (Mistral's `chat.parse` → `chat.complete`, `parse_stream` → `stream`; openai's older `beta…stream` → `create`), do **not** patch both — that produces two spans and double-counts cost. Patch **only the primitive**; the delegating call is then traced through it as one span. Name the span after the primitive (`chat.complete` → `chat_completion_create`, `chat.stream` → `chat_completion_stream`) via `track_options.name` / `func.__name__` — this is unambiguous and always correct. The delegating call (e.g. `parse`) shares that name; the structured JSON is still in the output content, only the deserialized `.parsed` field is absent. Uses only the existing `track()` API — no reentrancy flags, no `contextvars`, no `set_tracing_active` (process-wide and racy). Verify in Phase 5 that the delegating call yields a single span with un-doubled cost.

> **Do not rename the span from a kwarg unless that kwarg faithfully identifies the mode for *every* caller of the primitive.** openai's `if kwargs.get("stream") is True: name = "chat_completion_stream"` is safe because `stream=True` on `create` always means streaming. The same trick with Mistral's `response_format` is **wrong**: a direct `chat.complete(response_format=…)` is a legitimate structured-output call, not a `parse`, so keying the name off `response_format` misclassifies it (real review finding on the Mistral PR). When the discriminator can't distinguish the delegating call from a direct call to the same primitive, don't rename — keep the primitive's name.

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

**Add a dedicated usage format — don't piggyback on another provider's parser.** Even when a provider's token-usage payload looks OpenAI-shaped, give it its own format in the `llm_usage` namespace rather than passing `provider=LLMProvider.OPENAI` to `try_build_opik_usage_or_log_error`. Reusing another provider's converter couples you to *its* schema changes and misattributes the parsed shape. The steps (see the Mistral change for the worked example): (1) add `llm_usage/<name>_usage.py` with a `class <Name>Usage(BaseOriginalProviderUsage)` declaring the token fields (+ nested details classes) and `from_original_usage_dict`; (2) add it to the `ProviderUsage` union and a `OpikUsage.from_<name>_dict` classmethod in `llm_usage/opik_usage.py`; (3) register `LLMProvider.<NAME>: [OpikUsage.from_<name>_dict]` in `llm_usage/opik_usage_factory.py`; (4) in the decorator, parse with `provider=LLMProvider.<NAME>`. Non-int fields (e.g. `prompt_audio_seconds`) are dropped from the backend flat dict automatically. Add unit tests under `tests/unit/llm_usage/test_<name>_usage.py` (parser + `build_opik_usage` factory path).

## Dependencies & imports

- The framework library is **imported inside the integration module** (`import mistralai` at the top of `opik_tracker.py`). That is safe because the module is only reached when a user does `from opik.integrations.<name> import ...`. Never import an integration from the `opik` package top level.
- Do **not** add the framework to `install_requires` unless it is already a core dependency (openai and litellm are; most are not). Users install the framework themselves.
- `integrations/<name>/__init__.py` exports only the public entrypoint.
- If the integration must support multiple incompatible framework versions, branch at import time on `opik.semantic_version` (see `adk/__init__.py`).
- **Import only the framework's public API — never reach into internal modules.** Private paths (`mistralai.utils.eventstreaming.EventStream`, `mistralai.models.chatcompletionresponse.…`) move between releases and break the integration. Prefer top-level exports (`from mistralai import Mistral, ChatCompletionResponse`); check what's public with `hasattr(pkg, name)`. When you need a class that *isn't* exported (e.g. the stream type), don't import it — detect the returned object by its **protocol** (`hasattr(output, "__anext__")` → async stream, `hasattr(output, "__next__")` → sync stream; a pydantic response model has `__iter__` but neither) and operate on `type(output)` when you must patch its dunder methods. Real review finding on the Mistral PR.
- **Guard the minimum framework version.** Pin the floor in the test `requirements.txt` (`<lib>>=X,<next-major`), and add a runtime check in `track_<name>()` that raises a clear error — `f"Opik supports <lib>>=X, but {installed} is installed…"` — so users on an incompatible version get a message, not a cryptic `AttributeError`/`ImportError`. Read the installed version with `importlib.metadata.version("<lib>")` (robust: some versions don't expose `<lib>.__version__`) and compare via `opik.semantic_version.SemanticVersion.parse(...)`. Pick the floor empirically — bisect installs to the release where the public API you depend on first appears (for Mistral, `EventStream` landed in 1.3.0).

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
