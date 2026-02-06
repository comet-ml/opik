import os
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
from google.genai import types
from opik.decorator.error_info_collector import collect
from opik.integrations.adk import OpikTracer
from opik.opik_context import update_current_span

from .logger_config import logger
from .opik_backend_client import OpikBackendClient
from .trace_tools import TEST_PREFIX  # noqa: F401
from .trace_tools import (
    get_span_details_impl,
    get_spans_data_impl,
    get_trace_data_impl,
)

# Re-export TEST_PREFIX for backward compatibility with other modules
__all__ = ["TEST_PREFIX"]

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
    """Wrap a function to catch any exceptions and return a dictionary with a 'result' key."""
    from .config import settings

    @wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> dict[str, Any]:
        try:
            result = func(*args, **kwargs)
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
    trace_id: Optional[str] = None,
) -> list[Callable[..., Any]]:
    """Return the tools for the agent."""

    def get_trace_data() -> dict[str, Any]:
        """Return the trace data for the current trace.

        Returns:
            The trace data.
        """
        if trace_id is None:
            raise ValueError(
                "trace_id is required but was not provided to get_agent_tools"
            )
        return get_trace_data_impl(opik_client, trace_id)

    def get_span_details(
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
        return get_span_details_impl(
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
    trace_id: Optional[str] = None,
) -> list[Callable[..., Any]]:
    """Return the tools for the agent, tools calls are tracked with Opik and errors are catched as ADK don't catch tool call errors"""

    return [
        safe_wrapper(tool)
        for tool in get_agent_tools(opik_client, trace_id)
    ]


async def create_session(
    user_id: str,
    trace_id: str,
    session_id: Optional[str] = None,
    session_service: Optional[BaseSessionService] = None,
):
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


def get_runner(agent: Agent, session_service: BaseSessionService):
    return Runner(agent=agent, app_name=APP_NAME, session_service=session_service)


async def single_turn_conversation(
    runner: Runner, session_id: str, user_id: str, query: str
) -> Optional[str]:
    # Wrap the input into Google GenAI format
    content = types.Content(role="user", parts=[types.Part(text=query)])

    # Run the agent
    events = runner.run(user_id=user_id, session_id=session_id, new_message=content)

    final_response = None

    content_parts = []

    for event in events:
        if event.is_final_response():
            if event.content:
                content_parts.append(event.content)
            if event.content and event.content.parts:
                final_response = event.content.parts[0].text

    assert final_response is not None, f"Final response is None, {content_parts}"

    return final_response


async def agent_loop_async(agent, query: str, user_id: str, trace_id: str):
    session_service, session_id, session = await create_session(user_id, trace_id)
    # Initialize the root agent runner
    runner = get_runner(agent=agent, session_service=session_service)

    final_response = None

    try:
        while True:
            final_response = await single_turn_conversation(
                runner, session_id, user_id, query
            )

            if final_response is not None:
                print("Agent response:", final_response)
            else:
                print("Final response received but content is empty or malformed.")

            query = input("You: ")
    except Exception as e:
        print(f"Error: {e}")

    print("#" * 80)
    print("FINAL RESPONSE")
    print(final_response)
    print("#" * 80)

    # Cleanup: Close all sessions and shut down background resources
    await runner.close()

    return final_response


def get_agent(
    opik_client: OpikBackendClient,
    opik_metadata: Optional[dict[str, Any]] = None,
    trace_id: Optional[str] = None,
):
    from .config import settings
    
    # Ensure trace_id is in metadata if provided
    if opik_metadata is None:
        opik_metadata = {}
    if trace_id is not None and "trace_id" not in opik_metadata:
        opik_metadata["trace_id"] = trace_id

    # Only create OpikTracer if internal logging is configured
    tracker = OpikTracer(metadata=opik_metadata) if settings.opik_internal_url else None

    # Fetch spans data if trace_id is provided
    spans_data_str = "[]"
    if trace_id is not None:
        try:
            import json

            trace = opik_client.get_trace(trace_id)
            project_id = trace["project_id"]
            spans_data = get_spans_data_impl(opik_client, trace_id, project_id)
            spans_data_str = json.dumps({"result": spans_data}, indent=2)
            logger.info(f"Loaded spans data for trace {trace_id} into system prompt")
        except Exception as e:
            logger.warning(
                f"Failed to load spans data for system prompt: {e}", exc_info=True
            )
            spans_data_str = f"[]  # Note: Failed to load spans data: {str(e)}"

    instructions = INSTRUCTIONS.format(
        trace_schema=TRACE_SCHEMA,
        span_schema=SPAN_SCHEMA,
        spans_data=spans_data_str,
    )

    model_name = os.environ.get("AGENT_MODEL", "openai/gpt-4.1")
    
    # Configure model with optional reasoning_effort
    model_kwargs = {}
    reasoning_effort = os.environ.get("AGENT_REASONING_EFFORT")
    if reasoning_effort:
        model_kwargs["reasoning_effort"] = reasoning_effort
    
    llm_model = LiteLlm(model_name, **model_kwargs)

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
        agent_kwargs.update({
            "before_agent_callback": tracker.before_agent_callback,
            "after_agent_callback": tracker.after_agent_callback,
            "before_model_callback": tracker.before_model_callback,
            "after_model_callback": tracker.after_model_callback,
            "before_tool_callback": tracker.before_tool_callback,
            "after_tool_callback": tracker.after_tool_callback,
        })
    
    root_agent = Agent(**agent_kwargs)
    return root_agent


async def extract_tool_calls(
    session_service: BaseSessionService, session_id: str, user_id: str
) -> list[dict[str, Any]]:
    session = await session_service.get_session(
        app_name=APP_NAME, user_id=user_id, session_id=session_id
    )

    if not session:
        raise ValueError(f"Session {session_id} not found")

    tool_calls = []

    for event in session.events:
        function_calls = event.get_function_calls()
        if function_calls:
            for function_call in function_calls:
                tool_calls.append(
                    {"name": function_call.name, "kwargs": function_call.args}
                )

    return tool_calls


async def extract_tool_calls_with_responses(
    session_service: BaseSessionService, session_id: str, user_id: str
) -> list[dict[str, Any]]:
    """Extract tool calls with their responses from the session.

    Uses ADK's reliable get_function_calls() and get_function_responses() methods
    to extract both the tool calls and their responses, pairing them by function name.

    Args:
        session_service: The session service to use.
        session_id: The session ID.
        user_id: The user ID.

    Returns:
        List of tool calls with their responses, each containing:
        - name: The tool name
        - kwargs: The tool arguments
        - response: The tool response data (if available)
    """
    session = await session_service.get_session(
        app_name=APP_NAME, user_id=user_id, session_id=session_id
    )

    if not session:
        raise ValueError(f"Session {session_id} not found")

    tool_calls_with_responses = []
    pending_calls: dict[str, dict[str, Any]] = {}  # Map function name to pending call

    for event in session.events:
        # Extract function calls using ADK's reliable method
        function_calls = event.get_function_calls()
        if function_calls:
            for function_call in function_calls:
                call_data = {
                    "name": function_call.name,
                    "kwargs": dict(function_call.args) if function_call.args else {},
                    "response": None,
                }
                pending_calls[function_call.name] = call_data
                tool_calls_with_responses.append(call_data)

        # Extract function responses using ADK's reliable method
        function_responses = event.get_function_responses()
        if function_responses:
            for function_response in function_responses:
                func_name = function_response.name
                response_data = function_response.response

                # Find the most recent pending call with this name
                if func_name in pending_calls:
                    pending_calls[func_name]["response"] = response_data
                    # Remove from pending after matching
                    del pending_calls[func_name]

    return tool_calls_with_responses
