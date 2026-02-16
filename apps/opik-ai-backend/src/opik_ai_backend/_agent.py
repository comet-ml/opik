"""Trace-analyzer agent: builds ADK agents with Opik trace tools and manages sessions.

Exposes get_agent() to create an ADK Agent that can analyze traces/spans via
OpikBackendClient, get_runner() for session-aware execution, and create_session()
for session lifecycle. Trace data is loaded into the system prompt so the LLM
has full context. When trace_id is missing or spans fail to load, the agent
still works but with degraded context.
"""

import json
import time
import traceback
import uuid
from functools import wraps
from typing import Any, Callable, Optional

import opik
from google.adk.agents import Agent
from google.adk.events import Event, EventActions
from google.adk.models.lite_llm import LiteLlm
from google.adk.runners import Runner
from google.adk.sessions import BaseSessionService, InMemorySessionService
from opik.decorator.error_info_collector import collect
from opik.integrations.adk import OpikTracer
from opik.opik_context import update_current_span

from .auth_dependencies import UserContext
from .logger_config import logger
from .opik_backend_client import OpikBackendClient
from .trace_tools import get_span_details_impl, get_spans_data_impl, get_trace_data_impl

APP_NAME = "trace-analyzer"


TRACE_SCHEMA = """OpikTrace: Top-level execution container
Key fields: id, name, project_id, start_time, end_time, duration, input, output, error_info, span_count, llm_span_count, total_estimated_cost, usage, feedback_scores, metadata, tags"""

SPAN_SCHEMA = """OpikSpan: Individual operation within a trace
Key fields: id, name, type, trace_id, parent_span_id, start_time, end_time, duration, input, output, error_info, model, provider, total_estimated_cost, usage, feedback_scores, metadata, tags"""

INSTRUCTIONS = """## Role

You are an expert trace analyst for Opik with deep expertise in distributed systems debugging, performance optimization, and anomaly detection. You possess advanced skills in interpreting complex trace data and identifying subtle patterns across different domains. Your analytical approach is methodical, evidence-based, and focused on extracting maximum value from trace telemetry data, particularly for GenAI traces similar to Otel Trace in traditional monitoring.

## Task

Analyze GenAI trace data to identify specific issues, anomalies, and performance bottlenecks by systematically examining span inputs, outputs, and execution patterns across multiple span IDs within a single trace. Extract concrete insights about system behavior, detect scope mismatches, hallucinations, agent behavior problems, extraction failures, and other critical issues that impact system performance or correctness across a wide variety of industry contexts and use-cases.

## Instructions

### Issue Categories to Check

1. **Scope Mismatches**: Broad filters like "all"/"everything", missing constraints
2. **Hallucinations**: LLM outputs not supported by input data, fabricated facts
3. **Agent Issues**: Agents asking for confirmation instead of using tools
4. **Extraction Failures**: Null/empty values, missing required data
5. **Errors**: Exceptions, timeouts, KeyErrors - examine stack traces
6. **Performance**: High duration spans, excessive tokens, redundant calls

### Question-Specific Analysis Modes

**"Explain slowness"** → Focus on performance bottlenecks AND scope mismatches causing unnecessary processing
**"Identify anomalies"** → Comprehensive scan for scope mismatches, hallucinations, agent issues, extraction failures
**"Analyze trace"** → Full analysis across all patterns and potential issues
**"Find errors"** → Deep dive into exceptions, failures, and error conditions
**"Give summary"** → System architecture, workflow characteristics, and overall behavior patterns
**"Diagnose failure"** → Actual failure analysis, but also assess if trace completed successfully
**"Reduce costs"** → Analyze token usage, identify high-cost spans and their contribution to total cost

### Tool Usage

- **Spans data is ALREADY PROVIDED** at the end of this prompt (excludes input/output/metadata)
- **BE SELECTIVE**: Only fetch details for 2-5 most relevant spans
- **Use `get_span_details(span_id, include_input=..., include_output=..., include_metadata=...)`** to fetch span details efficiently in one call
- **Fetch ONLY what you need - each field adds tokens and cost:**
  - **input**: Data/parameters/prompts sent TO the operation. Useful for: user queries, function arguments, LLM prompts, tool parameters, understanding what data was provided or requested
  - **output**: Results/responses produced BY the operation. Useful for: LLM completions, function return values, tool results, agent responses, understanding what was generated or returned
  - **metadata**: Framework-specific details. Useful for: agent names, tool call info, workflow IDs, LangGraph state, internal configuration
- **Think about what evidence you need before fetching:**
  - Need to see what user complained about? → Fetch input
  - Need to verify what LLM generated? → Fetch output
  - Need to understand agent routing? → Fetch metadata
  - Analyzing performance/duration? → Often no need for input/output (use spans data)
  - Analyzing anomalies/errors? → May need input to see error messages or problematic requests

### Communication Protocol

**IMPORTANT: When calling tools, provide a brief status update (1 sentence, ~15-20 words) BEFORE the tool call:**
- Be specific about WHAT you're checking, but don't explain WHY or what you'll conclude
- Include key identifiers (span names, agent names) so users know what's being examined
- Good: "Checking the workflow root span and the four LLM calls for performance patterns."
- Good: "Examining the writer_agent, reviewer_agent, and their LLM spans for token usage."
- Good: "Looking at the outliner and writer LLM calls to understand the processing flow."
- Bad (too vague): "Fetching selected span details."
- Bad (too vague): "Examining the LLM calls and workflow"
- Bad (too verbose): "I'll retrieve detailed inputs, outputs, and metadata for the workflow root span plus three representative spans (a high-token LLM call, an early LLM call, and the writer_agent) to produce an accurate summary. I'll fetch those span details now."
- Aim for 15-20 words that tell users WHAT you're examining without explaining your analysis plan

Trace schema:
{trace_schema}

Span schema:
{span_schema}

Trace id: {{trace_id}}

## Pre-loaded Spans Data

The following spans data has been automatically loaded for this trace:

```json
{spans_data}
```

Use this data to identify relevant spans. If you need input, output, or metadata for specific spans, use the appropriate tools.
"""


def safe_wrapper(func: Callable[..., Any]) -> Callable[..., dict[str, Any]]:
    """Wrap an async function to catch any exceptions and return a dictionary with a 'result' key."""
    from .config import settings

    @wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> dict[str, Any]:
        try:
            result = await func(*args, **kwargs)
            if not isinstance(result, dict):
                return {"result": result}
            return result
        except Exception as e:
            err_msg = f"Tool {func.__name__} called with args {args} and kwargs {kwargs} failed with error: {e}"
            logger.error(err_msg, exc_info=True)
            # Only update span if internal logging is enabled
            if settings.opik_internal_url:
                update_current_span(error_info=collect(e))
            return {
                "result": err_msg,
                "type": "error",
                "raw_error_message": str(e),
                "traceback": traceback.format_exc(),
            }

    # Apply @opik.track decorator only if internal logging is configured
    if settings.opik_internal_url:
        wrapper = opik.track(wrapper)

    return wrapper


def get_agent_tools(
    opik_client: OpikBackendClient,
    trace_id: str,
) -> list[Callable[..., Any]]:
    """Return the tools for the agent."""

    async def get_trace_data() -> dict[str, Any]:
        """Return the trace data for the current trace.

        Returns:
            The trace data.
        """
        return await get_trace_data_impl(opik_client, trace_id)

    async def get_span_details(
        span_id: str,
        include_input: bool = False,
        include_output: bool = False,
        include_metadata: bool = False,
    ) -> dict[str, Any]:
        """Return selected details of a span in a single call. This is more efficient than calling get_span_input, get_span_output, and get_span_metadata separately.

        Args:
            span_id: The id of the span to get details for.
            include_input: Whether to include the span's input field.
            include_output: Whether to include the span's output field.
            include_metadata: Whether to include the span's metadata field.

        Returns:
            A dictionary containing the requested fields. Always includes span_id.
        """
        return await get_span_details_impl(
            opik_client,
            span_id,
            include_input,
            include_output,
            include_metadata,
        )

    return [
        get_trace_data,
        get_span_details,
    ]


def get_safe_agent_tools(
    opik_client: OpikBackendClient,
    trace_id: str,
) -> list[Callable[..., Any]]:
    """Return the tools for the agent, tool calls are tracked with Opik and errors are caught as ADK doesn't catch tool call errors"""

    return [safe_wrapper(tool) for tool in get_agent_tools(opik_client, trace_id)]


async def create_session(
    user_id: str,
    trace_id: str,
    session_id: Optional[str] = None,
    session_service: Optional[BaseSessionService] = None,
) -> tuple[BaseSessionService, str, Any]:
    """Create (or reuse) a session for the trace-analyzer agent.

    Initializes the session, injects the trace_id into session state, and returns
    the session service, session ID, and session object.
    """
    if session_id is None:
        session_id = str(uuid.uuid4())

    if session_service is None:
        session_service = InMemorySessionService()

    session = await session_service.create_session(
        app_name=APP_NAME, user_id=user_id, session_id=session_id
    )

    # Inject the trace id and project name into the session
    state_changes = {
        "trace_id": trace_id,
    }

    # --- Create Event with Actions ---
    actions_with_update = EventActions(state_delta=state_changes)
    # This event might represent an internal system action, not just an agent response
    system_event = Event(
        invocation_id="session_setup",
        author="system",
        actions=actions_with_update,
        timestamp=time.time(),
    )

    await session_service.append_event(session, system_event)

    # Note: Spans data is now injected into the system prompt in get_agent(),
    # not into the session history, to avoid repeating it with every LLM call

    return session_service, session_id, session


def get_runner(agent: Agent, session_service: BaseSessionService) -> Runner:
    """Create a Runner that executes the given agent using the provided session service.

    Wraps an already-initialized Agent with a BaseSessionService into a Runner,
    reusing the existing session service state and configuration.
    """
    return Runner(agent=agent, app_name=APP_NAME, session_service=session_service)


async def get_agent(
    opik_client: OpikBackendClient,
    trace_id: str,
    current_user: UserContext,
    opik_metadata: Optional[dict[str, Any]] = None,
) -> Agent:
    """Build an ADK Agent configured with trace-analysis tools and a pre-loaded system prompt.

    Fetches spans data for the trace and injects it into the system prompt so the LLM
    has full context from the first turn. If spans fail to load, the agent still works
    but with degraded context.

    Args:
        opik_client: Client for fetching trace/span data from Opik backend
        trace_id: The trace ID to analyze
        current_user: User authentication context (session token + workspace) to forward to AI proxy
        opik_metadata: Optional metadata for internal Opik tracking
    """
    from .config import settings

    # Ensure trace_id is in metadata
    if opik_metadata is None:
        opik_metadata = {}
    if "trace_id" not in opik_metadata:
        opik_metadata["trace_id"] = trace_id

    # Only create OpikTracer if internal logging is configured
    tracker = OpikTracer(metadata=opik_metadata) if settings.opik_internal_url else None

    # Fetch spans data for the trace
    spans_data_str = "[]"
    try:
        trace = await opik_client.get_trace(trace_id)
        project_id = trace["project_id"]
        spans_data = await get_spans_data_impl(opik_client, trace_id, project_id)
        spans_data_str = json.dumps({"result": spans_data}, indent=2)
        logger.info(f"Loaded spans data for trace {trace_id} into system prompt")
    except Exception as e:
        logger.warning(
            f"Failed to load spans data for system prompt: {e}", exc_info=True
        )
        spans_data_str = json.dumps(
            {"result": [], "error": f"Failed to load spans data: {e}"}
        )

    instructions = INSTRUCTIONS.format(
        trace_schema=TRACE_SCHEMA,
        span_schema=SPAN_SCHEMA,
        spans_data=spans_data_str,
    )

    model_name = settings.agent_model

    # Configure model with optional reasoning_effort
    model_kwargs = {}
    if settings.agent_reasoning_effort:
        model_kwargs["reasoning_effort"] = settings.agent_reasoning_effort

    # Forward user's auth credentials to the Opik AI proxy (same pattern as Playground)
    # The proxy will authenticate the user and use their configured provider API key
    extra_headers = {}
    if current_user.workspace_name:
        extra_headers["Comet-Workspace"] = current_user.workspace_name
    if current_user.session_token:
        extra_headers["Cookie"] = f"sessionToken={current_user.session_token}"

    # Point LiteLLM at the Opik backend's ChatCompletions proxy at /v1/private/chat/completions
    # LiteLLM has two transport paths:
    #   - OpenAI client (httpx): appends /chat/completions to api_base
    #   - aiohttp transport: uses api_base as-is
    # To handle both consistently, we set api_base to {backend}/v1/private and also
    # disable LiteLLM's aiohttp transport so it always uses the OpenAI client path.
    proxy_base_url = f"{settings.agent_opik_url}/v1/private"
    logger.info(
        f"Configuring LiteLLM with proxy: model={model_name}, "
        f"api_base={proxy_base_url}, workspace={current_user.workspace_name}, "
        f"has_session_token={current_user.session_token is not None}"
    )

    import litellm

    litellm.disable_aiohttp_transport = True

    llm_model = LiteLlm(
        model_name,
        api_base=proxy_base_url,
        api_key="not-checked",  # Required by OpenAI client lib, but proxy uses cookie auth
        extra_headers=extra_headers,
        **model_kwargs,
    )

    # Build agent kwargs, conditionally adding callbacks if tracker is available
    agent_kwargs = {
        "name": "data_analyzer",
        "model": llm_model,
        "description": (
            "This agent specializes in the analysis of GenAI traces and spans, leveraging data logged to Opik, a dedicated GenAI monitoring platform. It is designed to interpret and provide insights into the performance, behavior, and potential issues within your GenAI applications by examining the detailed trace and span data captured."
        ),
        "instruction": instructions,
        "tools": get_safe_agent_tools(opik_client, trace_id),
    }

    # Add OpikTracer callbacks only if internal logging is configured
    if tracker is not None:
        agent_kwargs.update(
            {
                "before_agent_callback": tracker.before_agent_callback,
                "after_agent_callback": tracker.after_agent_callback,
                "before_model_callback": tracker.before_model_callback,
                "after_model_callback": tracker.after_model_callback,
                "before_tool_callback": tracker.before_tool_callback,
                "after_tool_callback": tracker.after_tool_callback,
            }
        )

    root_agent = Agent(**agent_kwargs)
    return root_agent
