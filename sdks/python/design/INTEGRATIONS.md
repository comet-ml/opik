# Opik Python SDK: Integrations Architecture

## Table of Contents

- [Overview](#overview)
- [Integration Patterns](#integration-patterns)
- [Method Patching Integrations](#method-patching-integrations)
- [Callback Integrations](#callback-integrations)
- [Hybrid Integrations](#hybrid-integrations)
- [Streaming Strategies](#streaming-strategies)
- [Token Usage and Cost Tracking](#token-usage-and-cost-tracking)

## Overview

The SDK provides automatic tracking for 12+ LLM frameworks through three architectural patterns. Integrations are designed to be lightweight, extensible, and framework-native.

### Integration Catalog

| Integration | Pattern | Location | Key Features |
|-------------|---------|----------|--------------|
| **OpenAI** | Method Patching | `integrations/openai/` | Multiple APIs, streaming, function calling |
| **Anthropic** | Method Patching | `integrations/anthropic/` | Messages API, delta accumulation |
| **Bedrock** | Method Patching | `integrations/bedrock/` | Multi-format aggregators, extensible |
| **Google GenAI** | Method Patching | `integrations/genai/` | Multi-modal support |
| **AISuite** | Method Patching | `integrations/aisuite/` | Unified interface |
| **LangChain** | Callback | `integrations/langchain/` | BaseTracer, provider extractors, external context support |
| **LlamaIndex** | Callback | `integrations/llama_index/` | Event parsing, dedicated client |
| **DSPy** | Callback | `integrations/dspy/` | Isolated context, graph visualization |
| **Haystack** | Callback | `integrations/haystack/` | Component-based |
| **ADK** | Hybrid | `integrations/adk/` | OpenTelemetry interception + callbacks |
| **CrewAI** | Hybrid | `integrations/crewai/` | Method wrapping + LiteLLM delegation |

## Integration Patterns

### Pattern Selection

```
Library Architecture Analysis:

Does library provide callbacks/hooks?
    │
    ├─► Yes ─► Callbacks reliable and in-context?
    │           │
    │           ├─► Yes ─► Pure Callback
    │           │           (LangChain, LlamaIndex, DSPy, Haystack)
    │           │
    │           └─► No ─► Hybrid (Callback + Patching)
    │                       (ADK, CrewAI)
    │
    └─► No ─► Method Patching
                (OpenAI, Anthropic, Bedrock, GenAI, AISuite)
```

### Callback Reliability Issues

**Why callbacks alone may be insufficient**:

1. **Completion guarantee**: Some frameworks skip END callbacks on exceptions
2. **Context isolation**: Callbacks may execute in different thread/context than original call
3. **Timing**: Callbacks may fire with delays, complicating context management

**Solution**: Add patching/integration for OpenTelemetry interception (ADK) or external dependency tracking (CrewAI).

## Method Patching Integrations

### Architecture

Method patching wraps client methods to intercept calls:

```
track_library(client) → Wraps methods → client.method() intercepted
                                             ↓
                                    BaseTrackDecorator
                                             ↓
                            _start_span_inputs_preprocessor
                            (extract input, create span)
                                             ↓
                                  Call original method
                                             ↓
                                    _streams_handler
                            (check if output is stream)
                                             ↓
                                    ┌────────┴────────┐
                                    │                 │
                                Stream?            Not stream
                                    │                 │
                            Patch stream              │
                            Defer finalization        │
                            Return patched            │
                                    │                 │
                                    └────────┬────────┘
                                             ↓
                            _end_span_inputs_preprocessor
                            (extract output, usage, finalize span)
                            (called immediately for non-streaming,
                             or in finally block for streaming)
```

**All method patching integrations are idempotent**: Use `opik_tracked` marker to prevent double-wrapping.

### OpenAI Integration

**Files**:
- `opik_tracker.py` - Main entry point, wraps client methods
- `openai_chat_completions_decorator.py` - Chat completions decorator
- `openai_responses_decorator.py` - Responses API decorator
- `stream_patchers.py` - Stream iteration patching
- `chat_completion_chunks_aggregator.py` - Chunk aggregation
- `response_events_aggregator.py` - Response events aggregation

**Wrapped Methods**:
- `chat.completions.create()` - Standard chat API
- `beta.chat.completions.parse()` - Structured outputs
- `responses.create()` - Responses API

**Streaming Support**: Handles `openai.Stream`, `openai.AsyncStream`, and `ChatCompletionStreamManager`.

### Anthropic Integration

**Files**:
- `opik_tracker.py` - Main entry point
- `messages_create_decorator.py` - Messages decorator
- `stream_patchers.py` - Stream/context manager patching

**Wrapped Methods**:
- `messages.create()` - Both standard and streaming
- `messages.stream()` - Context manager pattern

**Key Implementation Detail**: **Delta Accumulation**

Anthropic streams delta events (not complete chunks) that must be accumulated. Event accumulator builds complete message by merging deltas progressively.

**Location**: `stream_patchers.py` - See accumulation logic

### Bedrock Integration

**Files**:
- `opik_tracker.py` - Main entry point
- `converse/converse_decorator.py` - Converse API
- `invoke_model/invoke_model_decorator.py` - Legacy InvokeModel API
- `invoke_model/chunks_aggregator/` - Extensible aggregator system

**Wrapped Methods**:
1. `client.converse()` - Unified Converse API
2. `client.invoke_model()` - Legacy API (multiple formats)
3. `client.invoke_agent()` - Agent invocations

**Key Implementation Detail**: **Extensible Multi-Format Aggregator**

**Problem**: Bedrock supports multiple model formats (Claude, Nova, Llama, Mistral) with different streaming structures.

**Solution**: Registry pattern with pluggable aggregators.

**Architecture** (`invoke_model/chunks_aggregator/`):
- `base.py` - `ChunkAggregator` protocol
- `format_detector.py` - Detection registry + aggregator registry
- `claude.py`, `nova.py`, `llama.py`, `mistral.py` - Format-specific aggregators
- `api.py` - Public interface: `detect_format()` + `aggregate_chunks_to_dataclass()`

**Extensibility**: Add new format by creating module + registering in `format_detector.py`. Zero changes to existing code.

**Benefits**: Open/Closed Principle, isolated testing, clear separation of concerns.

**Documentation**: See `EXTENDING.md` and `README.md` in `chunks_aggregator/` directory.

### Google GenAI Integration

**Files**:
- `opik_tracker.py` - Main entry point
- `generate_content_decorator.py` - Content generation decorator
- `stream_wrappers.py` - Stream handling
- `generations_aggregators.py` - Chunk aggregation

**Features**: Multi-modal support (text, images), streaming responses.

### AISuite Integration

**Files**:
- `opik_tracker.py` - Main entry point
- `aisuite_decorator.py` - Decorator implementation

**Pattern**: Similar to OpenAI (unified interface across providers).

## Callback Integrations

### Architecture

Callback integrations implement framework's callback interface:

```
Framework execution → Fires events → Callback methods
                                             ↓
                                    on_start() - Create span/trace
                                    on_end() - Update and send
                                    on_error() - Capture error, finalize
```

### LangChain Integration

**Files**:
- `opik_tracer.py` - Implements `BaseTracer`
- `langgraph_tracer_injector.py` - Graph configuration injection for LangGraph
- `langgraph_async_context_bridge.py` - Context propagation for async LangGraph nodes
- `provider_usage_extractors/` - Provider-specific usage extraction
- `helpers.py` - Utility functions
- `base_llm_patcher.py` - Adds `base_url` to LLM dict (for provider ID)

**Pattern**: Pure callback (extends `langchain_core.tracers.BaseTracer`)

**Key Feature**: **Supports parent-child relations with external Opik spans/traces**

When used within `@track` decorated functions or existing Opik trace context:
- Detects existing trace in `context_storage`
- Creates LangChain spans as children of current Opik span
- Maintains proper hierarchy between Opik and LangChain operations

Example:
```python
@opik.track                          # Opik trace + span
def my_function():
    chain.invoke(..., callbacks=[OpikTracer()])  # LangChain spans as children
```

**State Management**:
- `_span_data_map: Dict[UUID, SpanData]` - Maps LangChain run_id to Opik span
- `_created_traces_data_map: Dict[UUID, TraceData]` - Maps run_id to trace
- `_externally_created_traces_ids: Set[str]` - Tracks external traces

**Callback Methods** (implements full `BaseTracer` interface):

**Chain callbacks**:
- `_on_chain_start(run)` → Check for existing trace, create span as child if exists
- `_on_chain_end(run)` → Finalize span, send to backend
- `_on_chain_error(run)` → Capture error info, finalize span

**LLM callbacks**:
- `on_chat_model_start(...)` → Special handling for chat models
- `_on_chat_model_start(run)` → Internal processing
- `_on_llm_start(run)` → Create LLM span (type="llm"), extract provider
- `_on_llm_end(run)` → Extract usage via provider extractors, send span
- `_on_llm_error(run)` → Capture error, finalize span

**Tool callbacks**:
- `_on_tool_start(run)` → Create tool span (type="tool")
- `_on_tool_end(run)` → Finalize tool span
- `_on_tool_error(run)` → Capture error, finalize span

Error callbacks ensure spans finalized even when LangChain operations fail.

**Key Implementation Detail**: **Provider-Specific Usage Extractors**

**Location**: `provider_usage_extractors/`

**Challenge**: Each LangChain provider stores usage in different locations/formats within the `Run` object.

**Solution**: Registry pattern with provider-specific extractors.

Extractors:
- `OpenAIUsageExtractor` - Extracts from `run.outputs.llm_output.token_usage`
- `AnthropicUsageExtractor` - Handles Anthropic format
- `BedrockUsageExtractor` - Handles Bedrock format
- `GoogleUsageExtractor` - Handles Google format
- See `usage_extractor.py` for full registry

Each extractor knows where to find usage in that provider's Run structure.

**LangGraph Support**:

The integration provides enhanced support for LangGraph through:

1. **`track_langgraph()` Function**: High-level wrapper that injects `OpikTracer` into the graph's default configuration, eliminating the need to pass `config={"callbacks": [opik_tracer]}` on every invocation.

2. **Automatic Graph Visualization**: Extracts and stores Mermaid graph structure in trace metadata via `OpikTracer.set_graph()` method.

3. **Async Context Bridge**: `extract_current_langgraph_span_data()` helper for propagating trace context to `@track`-decorated functions in async LangGraph nodes.

**Usage Pattern**:
```python
from opik.integrations.langchain import OpikTracer, track_langgraph
from langgraph.graph import StateGraph, START, END

# Build and compile graph
builder = StateGraph(State)
builder.add_node("my_node", my_node_function)
builder.add_edge(START, "my_node")
builder.add_edge("my_node", END)
app = builder.compile()

# Track once
opik_tracer = OpikTracer(tags=["production"])
app = track_langgraph(app, opik_tracer)

# All invocations automatically tracked
result = app.invoke({"message": "Hello"})
```

**Implementation Details**:
- `langgraph_tracer_injector.py` - Injects `OpikTracer` into graph's default config
- `langgraph_async_context_bridge.py` - Extracts span data from LangGraph config for async context propagation
- `OpikTracer.set_graph()` - Stores graph visualization in `_trace_default_metadata["_opik_graph_definition"]`

### LlamaIndex Integration

**Files**:
- `callback.py` - Implements `BaseCallbackHandler`
- `event_parsing_utils.py` - Parses LlamaIndex event payloads

**Event Handling**:
- `on_event_start(event_type, payload, event_id, parent_id)` → Parse payload, create span
- `on_event_end(event_type, payload, event_id)` → Parse output/usage, send span

**Event Parser** (`event_parsing_utils.py`): Extracts data from payloads based on `event_type` (EMBEDDING, QUERY, LLM, etc.).

### DSPy Integration

**Files**:
- `callback.py` - Implements `dspy.utils.callback.BaseCallback`
- `graph.py` - Mermaid graph builder for DSPy programs

**Callbacks**:
- `on_module_start/end()` - DSPy module execution
- `on_lm_start/end()` - LM calls (extracts provider/model from "provider/model" format)
- `on_tool_start/end()` - Tool executions

**Key Implementation Detail**: **Isolated Context Storage**

Uses dedicated `OpikContextStorage` instance (not global). Prevents interference when used alongside `@track` decorator.

**Why?** DSPy callbacks can coexist with `@track` decorated functions. Separate storage prevents context conflicts.

**Graph Visualization**: Builds Mermaid diagram of DSPy program structure (`graph.py`).

### Haystack Integration

**Files**:
- `opik_connector.py` - Component added to pipeline
- `opik_tracer.py` - Tracer for pipeline execution
- `converters.py` - Convert Haystack objects to Opik format

**Pattern**: Component-based (added to pipeline, observes without modifying data flow).

## Hybrid Integrations

### ADK Integration

**Files**:
- `opik_tracer.py` - Agent callbacks
- `patchers/adk_otel_tracer/opik_adk_otel_tracer.py` - OpenTelemetry tracer
- `recursive_callback_injector.py` - Recursive callback injection
- `graph/mermaid_graph_builder.py` - Agent graph visualization
- `patchers/patchers.py` - Global patches

**Why Hybrid**: ADK uses OpenTelemetry for internal tracing + provides agent callbacks.

**Dual Approach**:

1. **OpenTelemetry Patching** (`patchers/adk_otel_tracer/opik_adk_otel_tracer.py`):
   - Intercepts `start_span()` calls from ADK
   - Creates Opik spans instead
   - Returns `INVALID_SPAN` (no-op for OpenTelemetry)
   - Skips internal ADK spans via `_ADK_INTERNAL_SPAN_NAME_SKIP_LIST`

2. **Agent Callbacks** (`opik_tracer.py`):
   - `before/after_agent_callback`
   - `before/after_model_callback`
   - `before/after_tool_callback`
   - Recursively injected into agent tree (`recursive_callback_injector.py`)

**Key Implementation Details**:

1. **OpenTelemetry Interception**: Instead of dual tracing (OTel + Opik), intercepts OTel tracer to create only Opik spans. Single tracing backend, no OpenTelemetry overhead. Callbacks is used only to update spans and traces, but it's OTel tracer that is responsible
for creating them and working with context (it's done to benefit from reliability of OTel context manager)

2. **Graph Visualization** (`graph/mermaid_graph_builder.py`): Generates Mermaid diagram of agent structure including:
   - Agent types (Sequential, Loop, Parallel, LLM)
   - Tools and their connections
   - Subagent relationships
   - Stored in trace metadata `_opik_graph_definition`

### CrewAI Integration

**Files**:
- `opik_tracker.py` - Main tracking setup
- `crewai_decorator.py` - Decorator for CrewAI methods
- `flow_patchers.py` - Flow class patching

**Why Hybrid**: CrewAI methods wrapped + LiteLLM used for LLM tracking + direct provider client patching for v1.0.0+.

**Approach**:
1. **Method Wrapping**: Wrap `Crew.kickoff`, `Agent.execute_task`, `Task.execute_sync`
2. **LiteLLM Delegation**: Enable `litellm.track_litellm()` (CrewAI uses LiteLLM internally for v0.x)
3. **Flow Patching**: Patch `Flow.__init__` to auto-wrap dynamically registered methods (v1.0.0+ only)
4. **Provider Client Patching**: For v1.0.0+, directly patch OpenAI, Anthropic, Gemini, and Bedrock clients when `crew` argument is provided

**Key Implementation Details**:

1. **LiteLLM Delegation**: Reuses existing LiteLLM integration instead of duplicating LLM tracking logic.

2. **Flow Patching** (`flow_patchers.py`): Patches constructor to wrap methods registered via `@start`, `@listen` decorators. Gracefully handles missing `Flow` class (not available in CrewAI < v1.0.0).

3. **Graceful Degradation**: Handles missing provider libraries gracefully:
   - If a provider library (e.g., `crewai.llms.providers.openai.completion`) is not installed, logs debug message and continues
   - If tracking a specific provider client fails, logs warning and continues with other providers
   - Ensures integration doesn't fail if some optional dependencies are missing

**Usage**:
```python
# For CrewAI v0.x (LiteLLM-based)
track_crewai(project_name="my-project")

# For CrewAI v1.0.0+ (direct provider clients)
crew = Crew(agents=[...], tasks=[...])
track_crewai(project_name="my-project", crew=crew)  # crew argument enables LLM client tracking
```

## Streaming Strategies

### Streaming Challenges

1. **Deferred finalization**: Can't finalize span until stream consumed
2. **User-controlled consumption**: User determines when/if stream is fully consumed
3. **Chunk accumulation**: Need complete response for logging
4. **Error handling**: Exceptions during iteration
5. **Context cleanup**: Must finalize even if stream abandoned

### Strategy 1: Monkey-Patch Class Iterator

**Used by**: OpenAI (`openai.Stream`), Anthropic (`anthropic.Stream`)

**Files**: `stream_patchers.py` in each integration

**Approach**:
1. Save original `__iter__` from class
2. Create wrapper that accumulates chunks
3. Replace class method: `Stream.__iter__ = wrapper`
4. Mark instance: `stream.opik_tracked_instance = True`
5. Attach span/trace data to instance
6. Wrapper checks marker before processing

**Key Pattern - Context Pop Before Streaming**:

Before returning stream, pop span/trace from context:
```python
def _streams_handler(self, output, ...):
    if is_stream(output):
        # Pop BEFORE returning (stream consumed later)
        span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
        return patch_stream(output, span_to_end, trace_to_end, ...)
```

**Why**: Stream consumption happens after decorator returns. Popping prevents nested calls from seeing stale context.

**Key Pattern - Finalization Guarantee**:

All stream wrappers use `finally`:
```python
def wrapper(self):
    try:
        accumulated = []
        for item in original(self):
            accumulated.append(item)
            yield item
    finally:
        # ALWAYS runs - even if stream not fully consumed
        finalize_span(aggregator(accumulated), ...)
```

**Why**: User might break early or exception occurs. Span must finalize.

### Strategy 2: Context Manager Patching

**Used by**: Anthropic (`MessageStreamManager`)

**Approach**:
- Patch `__enter__` and `__exit__` of stream manager
- Accumulate during iteration (between enter/exit)
- Finalize in `__exit__`

**Files**: `stream_patchers.py`

Suitable for stream managers that use `with` statement pattern.

### Strategy 3: Generator Wrapper

**Used by**: Some Bedrock/GenAI cases

**Location**: `opik/decorator/generator_wrappers.py`

**Approach**: Wrap generator without modifying library classes. Returns custom proxy that finalizes in `__del__` or explicit close.

## Token Usage and Cost Tracking

### OpikUsage - Standardized Format

**Location**: `opik/llm_usage/opik_usage.py`

All providers map to standardized format:
```python
class OpikUsage(pydantic.BaseModel):
    completion_tokens: Optional[int]
    prompt_tokens: Optional[int]
    total_tokens: Optional[int]
    provider_usage: Optional[BaseOriginalProviderUsage]  # Original preserved
```

### Usage Factory - Registry Pattern

**Location**: `opik/llm_usage/opik_usage_factory.py`

Registry with builder functions per provider:
```python
_PROVIDER_TO_OPIK_USAGE_BUILDERS: Dict[Provider, List[Callable]] = {
    LLMProvider.OPENAI: [
        OpikUsage.from_openai_completions_dict,
        OpikUsage.from_openai_responses_dict,  # Multiple formats supported
    ],
    LLMProvider.ANTHROPIC: [OpikUsage.from_anthropic_dict],
    LLMProvider.BEDROCK: [OpikUsage.from_bedrock_dict],
    # ...
}
```

**Process**:
1. Integration extracts usage dict from response
2. Calls `build_opik_usage(provider, usage_dict)`
3. Factory tries each builder (supports multiple formats per provider)
4. Returns standardized `OpikUsage`

**Extensibility**: Add new provider by:
1. Create `MyProviderUsage` class
2. Add `from_myprovider_dict()` to `OpikUsage`
3. Register in factory

### Provider Enum

**Location**: `opik/types.py`

Supported providers for cost tracking:
- `OPENAI`, `ANTHROPIC`, `BEDROCK`
- `GOOGLE_VERTEXAI`, `GOOGLE_AI`
- `COHERE`, `GROQ`
- See `types.py` for complete list

### Cost Calculation

**SDK Responsibility**: Provide data
- `model`: Model name (e.g., "gpt-4")
- `provider`: Provider enum
- `usage`: Token counts (OpikUsage)
- `total_cost`: Optional override

**Backend Responsibility**: Calculate cost
- Pricing tables (model → price per token)
- Region-specific pricing (Bedrock)
- Token usage multiplication

**Note**: Integrations do **not** calculate cost - only provide data for backend.

## Summary

**Integration Patterns**:
- **Method Patching**: OpenAI, Anthropic, Bedrock, GenAI, AISuite
- **Callback**: LangChain, LlamaIndex, DSPy, Haystack
- **Hybrid**: ADK (callbacks + OTel), CrewAI (methods + LiteLLM)

**Streaming Strategies**:
- Class method patching (OpenAI, Anthropic Stream)
- Context manager patching (Anthropic MessageStreamManager)
- Generator wrapper (Bedrock, GenAI)

**Key Patterns**:
- **Idempotent tracking**: `opik_tracked` marker prevents double-wrapping
- **Context pop for streams**: Pop before returning stream (consumed later)
- **Finalization guarantee**: `finally` blocks ensure span completion
- **Registry patterns**: Pluggable providers/formats/extractors
- **Protocol-based**: Clear extension interfaces

**Notable Implementations**:
- **Bedrock**: Extensible aggregator system (add formats without modifying code)
- **ADK**: OpenTelemetry interception (single tracing backend)
- **LangChain**: External context support (composes with `@track`)
- **DSPy**: Isolated context storage (coexists with `@track`)
- **CrewAI**: LiteLLM delegation (reuses existing integration)

For implementation details, see source code in:
- `opik/integrations/` - All integration implementations
- `opik/llm_usage/` - Usage tracking and conversion
- `opik/decorator/` - Base decorator and streaming utilities

For more information, see:
- [API and Data Flow](API_AND_DATA_FLOW.md) - Core SDK architecture
- [Evaluation](EVALUATION.md) - Evaluation framework
- [Testing](TESTING.md) - Testing integrations
