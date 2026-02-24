import asyncio
import hashlib
import json
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any, Literal, Optional, Tuple

# Import settings early to check New Relic configuration
from .config import settings

# Conditionally initialize New Relic only if license key is configured
if settings.new_relic_license_key:
    os.environ["NEW_RELIC_LOG"] = "stdout"
    import newrelic.agent

    newrelic.agent.initialize()

import aiohttp
import openai
import sentry_sdk
import uvicorn
from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from google.adk.agents import RunConfig
from google.adk.agents.run_config import StreamingMode
from google.adk.cli.utils import common
from google.adk.events.event import Event
from google.adk.runners import Runner
from google.adk.sessions.base_session_service import BaseSessionService
from google.adk.sessions.database_session_service import DatabaseSessionService
from google.adk.sessions.in_memory_session_service import InMemorySessionService
from google.adk.sessions.session import Session
from google.genai import types

from ._agent import APP_NAME, create_session, get_agent, get_runner
from .analytics import track_conversation_resumed, track_conversation_started
from .auth_dependencies import UserContext, get_current_user
from .logger_config import logger
from .opik_backend_client import OpikBackendClient
from .opik_utils import (
    delete_feedback_from_opik,
    get_project_name_from_trace_id,
    submit_feedback_to_opik,
)

# Initialize Sentry SDK if DSN is configured
if settings.sentry_dsn:
    sentry_sdk.init(
        dsn=settings.sentry_dsn,
        environment=settings.sentry_environment,
        # Performance monitoring
        traces_sample_rate=settings.sentry_traces_sample_rate,
        # Profiling
        profiles_sample_rate=settings.sentry_profiles_sample_rate,
        # Data management
        send_default_pii=settings.sentry_send_default_pii,
    )
    logger.info("Sentry SDK initialized successfully")
else:
    logger.info("Sentry SDK not configured - skipping initialization")

url_prefix = settings.url_prefix


class AgentRunRequest(common.BaseModel):
    message: str
    streaming: bool = False


class LLMMessage(common.BaseModel):
    id: str
    role: Literal["user", "assistant"]
    content: str


class FeedbackRequest(common.BaseModel):
    value: int


class HistoryResponse(common.BaseModel):
    content: list[LLMMessage]


def get_text_from_event(
    event: Event,
) -> Tuple[Optional[str], Optional[Literal["user", "model"]]]:
    """
    Extract textual content from an event.

    Args:
        event: The event to extract the text from.

    Returns:
        A tuple containing the text and the role of the author.
    """

    if event.partial:
        return None, None

    if (
        event.content
        and event.content.parts
        and event.content.parts[0].text
        and event.content.role in ["user", "model"]
    ):
        return event.content.parts[0].text, event.content.role

    return None, None


def extract_messages_from_session(session: Session) -> list[LLMMessage]:
    """
    Extracts the messages from the session.

    Args:
        session: The session to extract the messages from.

    Returns:
        A list of LLMMessage objects.
    """

    messages = []
    for event in session.events:
        content, role = get_text_from_event(event)

        if content and role:
            opik_role = {"model": "assistant", "user": "user"}[role]
            messages.append(LLMMessage(id=event.id, role=opik_role, content=content))

    return messages


def get_session_id_from_trace_id(trace_id: str) -> str:
    return f"opik-ai-trace-analyzer-{trace_id}"


def generate_tool_call_id(function_call) -> str:
    """
    Extract the stable ID from a function call.
    ADK provides matching IDs for function calls and their responses.

    Args:
        function_call: The function call object from ADK.

    Returns:
        The function call ID from ADK (e.g., 'call_JXjQs4oBSpOVb7EwBVnT9dcV').
    """
    # ADK provides IDs that match between calls and responses
    if hasattr(function_call, "id") and function_call.id:
        return str(function_call.id)

    # Fallback: generate a hash if no ID is provided (shouldn't happen with ADK)
    logger.warning("Function call missing ID, generating fallback hash")
    data_to_hash = {
        "name": function_call.name,
        "args": dict(function_call.args) if function_call.args else {},
    }

    hash_input = json.dumps(data_to_hash, sort_keys=True)
    tool_hash = hashlib.sha256(hash_input.encode()).hexdigest()[:16]

    return f"tool-{tool_hash}"


@dataclass
class ParsedEventParts:
    """Parsed parts from an ADK event for SSE processing."""

    text_parts: list[Any]  # Parts containing text content
    tool_calls: list[
        dict[str, str]
    ]  # Extracted tool call info (id, name, display_name)
    tool_responses: list[dict[str, str]]  # Extracted tool response info (id, name)


def parse_event_parts(event: Event) -> ParsedEventParts:
    """
    Parse an event into its constituent parts in a single pass.

    Extracts text parts, tool calls, and tool responses from the event.
    Tool call/response parameters and data are NOT included for security.

    Args:
        event: The ADK event to parse.

    Returns:
        ParsedEventParts with text_parts, tool_calls, and tool_responses.
    """
    text_parts = []
    tool_calls = []
    tool_responses = []

    if not event.content or not event.content.parts:
        return ParsedEventParts(
            text_parts=text_parts,
            tool_calls=tool_calls,
            tool_responses=tool_responses,
        )

    # Extract text parts from event.content.parts
    for part in event.content.parts:
        # Collect text parts (parts that have text and are not function calls/responses)
        if hasattr(part, "text") and part.text:
            # Only include if it's a pure text part (not a function call/response)
            is_function_call = hasattr(part, "function_call") and part.function_call
            is_function_response = (
                hasattr(part, "function_response") and part.function_response
            )
            if not is_function_call and not is_function_response:
                text_parts.append(part)

    # Extract tool calls using ADK's helper method
    function_calls = event.get_function_calls()
    if function_calls:
        for function_call in function_calls:
            # Generate a stable ID that can be matched with the response
            tool_call_id = generate_tool_call_id(function_call)

            # Build display name - some tools have dynamic messages
            # Use {type:id} template format so frontend can replace with entity names
            # Example: {span:01978716-1435...} can be replaced with the span name
            if function_call.name == "get_span_details":
                args = function_call.args or {}
                span_id = args.get("span_id", "unknown")
                display_name = f"Fetching span details for {{span:{span_id}}}"
            elif function_call.name == "get_trace_data":
                display_name = "Retrieving trace information and metadata"
            else:
                display_name = f"Executing {function_call.name}"

            tool_calls.append(
                {
                    "id": tool_call_id,
                    "name": function_call.name,
                    "display_name": display_name,
                }
            )

    # Extract tool responses using ADK's helper method
    function_responses = event.get_function_responses()
    if function_responses:
        for function_response in function_responses:
            # ADK provides matching IDs for calls and responses
            if hasattr(function_response, "id") and function_response.id:
                tool_response_id = str(function_response.id)
            else:
                # Fallback if no ID (shouldn't happen with ADK)
                logger.warning("Function response missing ID, using fallback")
                tool_response_id = generate_tool_call_id(function_response)

            tool_responses.append(
                {
                    "id": tool_response_id,
                    "name": function_response.name,
                }
            )

    return ParsedEventParts(
        text_parts=text_parts,
        tool_calls=tool_calls,
        tool_responses=tool_responses,
    )


def build_response_sse_event(event: Event, text_parts: list[Any]) -> str:
    """
    Build a 'response' type SSE event containing only the fields the frontend needs.

    Args:
        event: The original ADK event (used for metadata).
        text_parts: List of text parts to include.

    Returns:
        SSE formatted string for the response event.
    """
    # Extract text from parts
    parts_data = []
    for part in text_parts:
        if hasattr(part, "text") and part.text:
            parts_data.append({"text": part.text})

    # Build minimal response event with only fields the frontend needs
    response_event = {
        "message_type": "response",
        "content": {
            "parts": parts_data,
        },
        "partial": event.partial,
        "invocationId": event.invocation_id,
        "id": event.id,
    }

    sse_event = json.dumps(response_event)
    logger.debug(
        "response SSE: parts=%d partial=%s id=%s invocation=%s",
        len(parts_data),
        event.partial,
        event.id,
        event.invocation_id,
    )
    return f"data: {sse_event}\n\n"


def build_tool_call_sse_events(
    event: Event, tool_calls: list[dict[str, str]]
) -> list[str]:
    """
    Build 'tool_call' type SSE events (one per tool call).

    Args:
        event: The original ADK event (used for metadata like timestamp, author).
        tool_calls: List of tool call info dicts.

    Returns:
        List of SSE formatted strings, one per tool call.
    """
    result = []
    for tool_call in tool_calls:
        tool_call_event = {
            "message_type": "tool_call",
            "tool_call": tool_call,
            "timestamp": event.timestamp,
            "id": tool_call["id"],
            "author": event.author,
        }
        sse_event = json.dumps(tool_call_event)
        logger.debug(
            "tool_call SSE: id=%s name=%s author=%s",
            tool_call["id"],
            tool_call.get("name", "unknown"),
            event.author,
        )
        result.append(f"data: {sse_event}\n\n")
    return result


def build_tool_response_sse_events(
    event: Event, tool_responses: list[dict[str, str]]
) -> list[str]:
    """
    Build 'tool_complete' type SSE events (one per tool response).

    Args:
        event: The original ADK event (used for metadata like timestamp, author).
        tool_responses: List of tool response info dicts.

    Returns:
        List of SSE formatted strings, one per tool response.
    """
    result = []
    for tool_response in tool_responses:
        tool_response_event = {
            "message_type": "tool_complete",
            "tool_response": tool_response,
            "timestamp": event.timestamp,
            "id": tool_response["id"],
            "author": event.author,
        }
        sse_event = json.dumps(tool_response_event)
        logger.debug(
            "tool_complete SSE: id=%s name=%s author=%s",
            tool_response["id"],
            tool_response.get("name", "unknown"),
            event.author,
        )
        result.append(f"data: {sse_event}\n\n")
    return result


def process_event_for_sse(event: Event) -> Optional[str]:
    """
    Process an event for SSE streaming.

    Parses the event into text parts, tool calls, and tool responses,
    then builds and returns the appropriate SSE events.

    IMPORTANT: Order matters! For events with both text and tool calls,
    the response event MUST come first so the frontend can finalize
    the in-progress streaming message before processing tool calls.

    Args:
        event: The ADK event to process.

    Returns:
        SSE formatted string(s) if there's content to emit, None otherwise.
        Can return multiple SSE events concatenated together.
    """
    # Parse the event into its parts
    parsed = parse_event_parts(event)

    result_events = []

    # 1. FIRST: Emit response event if there's text
    #    This finalizes any in-progress streaming message on the frontend
    if parsed.text_parts:
        result_events.append(build_response_sse_event(event, parsed.text_parts))

    # 2. THEN: Emit tool call events
    result_events.extend(build_tool_call_sse_events(event, parsed.tool_calls))

    # 3. FINALLY: Emit tool response events
    result_events.extend(build_tool_response_sse_events(event, parsed.tool_responses))

    return "".join(result_events) if result_events else None


async def retry_middleware(
    req: aiohttp.ClientRequest, handler: aiohttp.typedefs.Handler
) -> aiohttp.ClientResponse:
    """Retry middleware for transient server errors.

    Retries up to settings.retry_max_attempts times with exponential backoff.
    """
    retry_statuses = set(settings.retry_statuses)
    max_retries = settings.retry_max_attempts
    backoff_factor = settings.retry_backoff_factor

    for attempt in range(max_retries):
        resp = await handler(req)
        if resp.status not in retry_statuses or attempt == max_retries - 1:
            return resp
        delay = backoff_factor * (2**attempt)
        logger.warning(
            f"Request to {req.url} returned {resp.status}, "
            f"retrying in {delay}s (attempt {attempt + 1}/{max_retries})"
        )
        resp.close()
        await asyncio.sleep(delay)
    return resp


def get_fast_api_app(
    *,
    session_service_uri: Optional[str] = None,
    allow_origins: Optional[list[str]] = None,
) -> FastAPI:
    """Create and configure the trace analyzer FastAPI application.

    Sets up the full application including middleware, session management,
    and all route handlers for the trace analyzer agent.

    Args:
        session_service_uri: Database URI for persistent session storage.
            When provided, a DatabaseSessionService is created with up to 10
            connection attempts using exponential backoff (starting at 2s, max 30s).
            Raises RuntimeError if all attempts fail.
            When None, an InMemorySessionService is used instead.
        allow_origins: CORS origins to allow. When provided, CORSMiddleware
            is added with credentials, all methods, and all headers allowed.

    Returns:
        A configured FastAPI instance with these side effects:
        - A shared aiohttp.ClientSession (with retry middleware) is created
          on startup and closed on shutdown via the lifespan manager.
        - If settings.opik_internal_url is set, the Opik SDK is configured
          for internal observability (OpikTracer / @opik.track).
        - Analytics events are flushed on shutdown.
    """
    # Configure Opik SDK for internal logging (OpikTracer, @opik.track)
    # This sets the defaults for get_client_cached() and implicit SDK usage
    if settings.opik_internal_url is not None:
        import opik.config

        logger.info(
            f"Configuring internal logging to Opik at {settings.opik_internal_url}, "
            f"workspace={settings.opik_internal_workspace}, project={settings.opik_internal_project}"
        )
        opik.config.update_session_config("url_override", settings.opik_internal_url)
        if settings.opik_internal_api_key is not None:
            opik.config.update_session_config("api_key", settings.opik_internal_api_key)
        if settings.opik_internal_workspace is not None:
            opik.config.update_session_config(
                "workspace", settings.opik_internal_workspace
            )
        opik.config.update_session_config(
            "project_name", settings.opik_internal_project
        )
    else:
        logger.info("Internal logging to Opik is disabled (OPIK_INTERNAL_URL not set)")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # Startup: Create shared aiohttp session with retry middleware
        timeout = aiohttp.ClientTimeout(total=settings.opik_backend_timeout)
        app.state.http_session = aiohttp.ClientSession(
            base_url=settings.agent_opik_url,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
            timeout=timeout,
            middlewares=(retry_middleware,),
        )
        logger.info(
            f"Created shared aiohttp session with retry middleware for {settings.agent_opik_url}"
        )

        yield

        # Shutdown: Close aiohttp session and flush analytics
        from .analytics import flush_events

        await app.state.http_session.close()
        flush_events()
        logger.info("Closed shared aiohttp session and flushed analytics events")

    # Run the FastAPI server.
    app = FastAPI(lifespan=lifespan)

    if allow_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=allow_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    # Build the Session service
    session_service: BaseSessionService
    if session_service_uri:
        # Retry logic to handle transient DB errors during startup
        # (e.g., concurrent DDL from Opik backend migrations)
        max_retries = settings.session_service_max_retries
        retry_delay = settings.session_service_retry_delay
        last_exception: Exception | None = None

        for attempt in range(max_retries):
            try:
                session_service = DatabaseSessionService(
                    db_url=session_service_uri,
                    pool_pre_ping=True,
                    pool_recycle=settings.session_pool_recycle,
                )
                break
            except Exception as e:
                last_exception = e
                if attempt < max_retries - 1:
                    logger.warning(
                        f"Failed to initialize DatabaseSessionService (attempt {attempt + 1}/{max_retries}): {e}. "
                        f"Retrying in {retry_delay}s..."
                    )
                    time.sleep(retry_delay)
                    retry_delay = min(
                        retry_delay * 1.5, 30.0
                    )  # exponential backoff, max 30s
        else:
            # All retries exhausted
            raise RuntimeError(
                f"Failed to initialize DatabaseSessionService after {max_retries} attempts"
            ) from last_exception
    else:
        session_service = InMemorySessionService()

    @app.get(
        f"{url_prefix}/healthz",
        response_model_exclude_none=True,
    )
    async def health_check() -> dict[str, str]:
        health_status = {"status": "healthy"}

        # Check session service status
        if session_service_uri:
            health_status["session_service"] = "database"
        else:
            health_status["session_service"] = "in_memory"

        return health_status

    @app.get(
        f"{url_prefix}/trace-analyzer/session/{{trace_id}}",
        response_model_exclude_none=True,
    )
    async def get_session_history(
        trace_id: str, current_user: UserContext = Depends(get_current_user)
    ) -> HistoryResponse:
        session_id = get_session_id_from_trace_id(trace_id)
        session = await session_service.get_session(
            app_name=APP_NAME, user_id=current_user.user_id, session_id=session_id
        )
        if not session:
            raise HTTPException(status_code=404, detail="Session not found")

        messages = extract_messages_from_session(session)

        return HistoryResponse(content=messages)

    @app.delete(f"{url_prefix}/trace-analyzer/session/{{trace_id}}")
    async def delete_session(
        trace_id: str, current_user: UserContext = Depends(get_current_user)
    ):
        session_id = get_session_id_from_trace_id(trace_id)
        await session_service.delete_session(
            app_name=APP_NAME, user_id=current_user.user_id, session_id=session_id
        )

    def create_opik_client(user: UserContext) -> OpikBackendClient:
        """Create an OpikBackendClient wired to the shared session and user credentials."""
        return OpikBackendClient(
            session=app.state.http_session,
            session_token=user.session_token,
            workspace=user.workspace_name,
        )

    @app.put(f"{url_prefix}/trace-analyzer/session/{{trace_id}}/feedback")
    async def set_session_feedback(
        trace_id: str,
        req: FeedbackRequest,
        current_user: UserContext = Depends(get_current_user),
    ):
        """Set or update feedback for an OpikAssist conversation session."""
        if settings.opik_internal_url is None:
            logger.debug(
                "Internal monitoring not configured, skipping feedback submission"
            )
            return {"status": "ok"}

        # Validate feedback value
        if req.value not in (0, 1):
            raise HTTPException(
                status_code=400,
                detail={
                    "error": "Invalid feedback value",
                    "message": "Feedback value must be 0 (thumbs down) or 1 (thumbs up)",
                },
            )

        session_id = get_session_id_from_trace_id(trace_id)

        # Check if session exists
        session = await session_service.get_session(
            app_name=APP_NAME, user_id=current_user.user_id, session_id=session_id
        )
        if not session:
            raise HTTPException(
                status_code=404,
                detail={
                    "error": "Not found",
                    "message": f"Trace analyzer session not found for trace ID: {trace_id}",
                },
            )

        opik_client = create_opik_client(current_user)

        # Get trace to extract project_id
        try:
            trace = await opik_client.get_trace(trace_id)
            project_id = trace["project_id"]
        except Exception as e:
            logger.exception(f"Failed to get trace {trace_id}: {e}")
            raise HTTPException(
                status_code=500,
                detail={
                    "error": "Internal server error",
                    "message": "Failed to retrieve trace information.",
                },
            )

        # Submit feedback to Opik (this will close the thread first)
        try:
            await submit_feedback_to_opik(
                opik_client,
                session_id,
                req.value,
                project_id,
            )

            return {"status": "ok"}

        except Exception as e:
            logger.exception(f"Failed to submit feedback for trace {trace_id}: {e}")
            raise HTTPException(
                status_code=500,
                detail={
                    "error": "Internal server error",
                    "message": "Failed to submit feedback. Please try again later.",
                },
            )

    @app.delete(
        f"{url_prefix}/trace-analyzer/session/{{trace_id}}/feedback", status_code=204
    )
    async def delete_session_feedback(
        trace_id: str,
        current_user: UserContext = Depends(get_current_user),
    ):
        """Remove feedback for an OpikAssist conversation session."""
        if settings.opik_internal_url is None:
            logger.debug(
                "Internal monitoring not configured, skipping feedback deletion"
            )
            return

        session_id = get_session_id_from_trace_id(trace_id)

        # Check if session exists
        session = await session_service.get_session(
            app_name=APP_NAME, user_id=current_user.user_id, session_id=session_id
        )
        if not session:
            raise HTTPException(
                status_code=404,
                detail={
                    "error": "Not found",
                    "message": f"Trace analyzer session not found for trace ID: {trace_id}",
                },
            )

        opik_client = create_opik_client(current_user)

        # Delete feedback from Opik
        try:
            await delete_feedback_from_opik(
                opik_client,
                session_id,
                trace_id,
            )

        except ValueError as e:
            logger.exception(f"Failed to delete feedback for trace {trace_id}: {e}")
            raise HTTPException(
                status_code=404,
                detail={
                    "error": "Not found",
                    "message": f"No feedback found for trace ID: {trace_id}",
                },
            )
        except Exception as e:
            logger.exception(f"Failed to delete feedback for trace {trace_id}: {e}")
            raise HTTPException(
                status_code=500,
                detail={
                    "error": "Internal server error",
                    "message": "Failed to delete feedback. Please try again later.",
                },
            )

    @app.post(f"{url_prefix}/trace-analyzer/session/{{trace_id}}")
    async def agent_run_sse(
        trace_id: str,
        req: AgentRunRequest,
        current_user: UserContext = Depends(get_current_user),
    ) -> StreamingResponse:
        # First check if the session exists
        session_id = get_session_id_from_trace_id(trace_id)
        session = await session_service.get_session(
            app_name=APP_NAME, user_id=current_user.user_id, session_id=session_id
        )

        opik_client = create_opik_client(current_user)

        project_name = await get_project_name_from_trace_id(opik_client, trace_id)

        if not session:
            # If it doesn't exists, create a new sessions
            _, _, session = await create_session(
                user_id=current_user.user_id,
                trace_id=trace_id,
                session_service=session_service,
                session_id=session_id,
            )

            # Track conversation started event
            track_conversation_started(
                user_id=current_user.user_id,
                workspace_name=current_user.workspace_name or "default",
                trace_id=trace_id,
                project_name=project_name,
                properties={
                    "session_id": session_id,
                    "streaming": req.streaming,
                },
            )
        else:
            # Track conversation resumed event
            messages = extract_messages_from_session(session)
            track_conversation_resumed(
                user_id=current_user.user_id,
                workspace_name=current_user.workspace_name or "default",
                trace_id=trace_id,
                project_name=project_name,
                message_count=len(messages),
                properties={
                    "session_id": session_id,
                    "streaming": req.streaming,
                },
            )

        # Convert the events to properly formatted SSE
        async def event_generator():
            try:
                stream_mode = StreamingMode.SSE if req.streaming else StreamingMode.NONE
                runner = await _get_runner_for_agent(
                    opik_client=opik_client,
                    trace_id=trace_id,
                    current_user=current_user,
                )

                message = types.Content(
                    role="user", parts=[types.Part(text=req.message)]
                )
                logger.debug(f"Starting runner.run_async for trace {trace_id}")
                event_count = 0
                async for event in runner.run_async(
                    user_id=current_user.user_id,
                    session_id=session_id,
                    new_message=message,
                    run_config=RunConfig(streaming_mode=stream_mode),
                ):
                    event_count += 1
                    # Process the event for SSE streaming
                    sse_event_str = process_event_for_sse(event)
                    if sse_event_str:
                        yield sse_event_str
            except openai.OpenAIError as e:
                logger.exception("LLM provider error in event_generator: %s", e)
                error_msg = json.dumps({"error": str(e)})
                yield f"data: {error_msg}\n\n"
            except Exception as e:
                logger.exception("Error in event_generator: %s", e)
                error_msg = json.dumps(
                    {
                        "error": "An internal error occurred while processing the request."
                    }
                )
                yield f"data: {error_msg}\n\n"

            logger.debug(
                "Runner completed for trace %s, %d events", trace_id, event_count
            )

        # Returns a streaming response with the proper media type for SSE
        return StreamingResponse(
            event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    async def _get_runner_for_agent(
        opik_client: OpikBackendClient,
        trace_id: str,
        current_user: UserContext,
    ) -> Runner:
        """Returns the runner for the given app."""
        root_agent = await get_agent(
            opik_client=opik_client,
            trace_id=trace_id,
            current_user=current_user,
            opik_metadata=None,
        )
        runner = get_runner(agent=root_agent, session_service=session_service)
        return runner

    return app


# Call the function to get the FastAPI app instance
# Ensure the agent directory name ('capital_agent') matches your agent folder
app: FastAPI = get_fast_api_app(
    session_service_uri=settings.session_service_uri,
    allow_origins=settings.allowed_origins,
)

if __name__ == "__main__":
    # Use the PORT environment variable provided by Cloud Run, defaulting to 8081
    uvicorn.run(app, host="0.0.0.0", port=settings.port)
