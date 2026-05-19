import contextvars
import logging
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Collection, Dict, Generator, Optional, Set

from opentelemetry.instrumentation.instrumentor import BaseInstrumentor  # type: ignore
from pyagentspec.tracing.events import Event, ExceptionRaised, LlmGenerationResponse
from pyagentspec.tracing.spans import LlmGenerationSpan, Span, ToolExecutionSpan
from pyagentspec.tracing.trace import Trace as AgentSpecTrace
from pyagentspec.tracing.trace import get_trace

import opik
import opik.datetime_helpers as datetime_helpers
import opik.id_helpers as id_helpers
import opik.llm_usage as llm_usage
from opik.api_objects import opik_client

logger = logging.getLogger(__name__)


def _ns_to_datetime(ns: Optional[int]) -> Optional[datetime]:
    """Convert nanosecond timestamp to a UTC-aware datetime, or None if not set."""
    if not ns:
        return None
    return datetime.fromtimestamp(ns / 1_000_000_000, tz=timezone.utc)


# AgentSpec clears its own trace contextvar BEFORE calling ``span_processor.shutdown()``,
# so by the time we reach shutdown ``get_trace()`` returns ``None``. We mirror the
# active AgentSpec trace id into our own ContextVar at startup so shutdown can still
# find the right state bucket. Since pyagentspec disallows nested traces in the same
# context, a plain ContextVar is sufficient.
_ACTIVE_AGENTSPEC_TRACE_ID: contextvars.ContextVar[Optional[str]] = (
    contextvars.ContextVar("opik_agentspec_active_trace_id", default=None)
)


class _PerTraceState:
    """Per-AgentSpec-trace state held by ``OpikSpanProcessor``.

    Keeping state on the instance directly would race when the same processor
    is shared across concurrent traces. We key state by ``AgentSpecTrace.id``
    instead so each active trace has its own bucket.
    """

    __slots__ = (
        "opik_trace_id",
        "name",
        "start_time",
        "thread_id",
        "span_ids_mapping",
        "first_llm_input",
        "last_llm_output",
        "lock",
    )

    def __init__(
        self,
        opik_trace_id: str,
        name: Optional[str],
        start_time: datetime,
        thread_id: Optional[str],
    ) -> None:
        self.opik_trace_id = opik_trace_id
        self.name = name
        self.start_time = start_time
        self.thread_id = thread_id
        self.span_ids_mapping: Dict[str, str] = {}
        self.first_llm_input: Optional[Dict[str, Any]] = None
        self.last_llm_output: Optional[Dict[str, Any]] = None
        # Guards the mutable fields above. Async span paths
        # (``on_start_async``/``on_end_async``) within the same trace may
        # interleave, so per-trace serialization is required.
        self.lock = threading.Lock()


class OpikSpanProcessor:
    """AgentSpec Opik SpanProcessor."""

    _EVENT_ATTRIBUTES_TO_HIDE_IN_IO: Set[str] = {
        "description",
        "id",
        "metadata",
        "name",
        "timestamp",
        "type",
    }

    def __init__(
        self,
        project_name: Optional[str] = None,
        host: Optional[str] = None,
        workspace: Optional[str] = None,
        api_key: Optional[str] = None,
        mask_sensitive_information: bool = True,
        thread_id: Optional[str] = None,
    ) -> None:
        """
        Forward AgentSpec tracing callbacks to an Opik client.

        Args:
            project_name: The project name to use in the Opik client.
            host: The host to use in the Opik client.
            workspace: The workspace to use in the Opik client.
            api_key: The API key to use in the Opik client.
            mask_sensitive_information: Whether to mask potentially sensitive
                information from the span and its events.
            thread_id: Default thread ID used to group multiple traces into a
                conversation thread. Can be overridden per-trace via
                :meth:`AgentSpecInstrumentor.instrument_context`.
        """
        self.mask_sensitive_information = mask_sensitive_information
        # Reuse the shared Opik client unless caller explicitly customizes it.
        # Building a fresh ``opik.Opik`` per processor instance is expensive
        # and pointless when the global config already matches.
        if any(v is not None for v in (project_name, host, workspace, api_key)):
            self.opik_client = opik.Opik(
                project_name=project_name,
                host=host,
                workspace=workspace,
                api_key=api_key,
            )
        else:
            self.opik_client = opik_client.get_client_cached()
        self.default_thread_id = thread_id
        # State keyed by AgentSpec trace id so concurrent traces sharing this
        # processor instance do not corrupt each other.
        self._states: Dict[str, _PerTraceState] = {}
        self._states_lock = threading.Lock()

    def _get_opik_span_type_from_agentspec_span_type(
        self, span: Span
    ) -> opik.types.SpanType:
        match span:
            case LlmGenerationSpan():
                return "llm"
            case ToolExecutionSpan():
                return "tool"
            case _:
                return "general"

    def _get_opik_model_from_agentspec_span(self, span: Span) -> Optional[str]:
        if isinstance(span, LlmGenerationSpan):
            return span.llm_config.name
        return None

    def _get_opik_span_name(self, span: Span) -> str:
        """Return a meaningful name for the span, using the tool name for tool spans."""
        # Honor explicit user-provided span name (anything different from the class default)
        if span.name and span.name != span.__class__.__name__:
            return span.name
        # For tool spans without an explicit name, prefer the underlying tool's name
        # over the class default "ToolExecutionSpan" (which the LangGraph adapter triggers).
        if isinstance(span, ToolExecutionSpan):
            tool = getattr(span, "tool", None)
            tool_name = getattr(tool, "name", None) if tool is not None else None
            if tool_name:
                return tool_name
        return span.__class__.__name__

    def _remove_unnecessary_attributes_for_event_display(
        self,
        event_dump: Dict[str, Any],
    ) -> Dict[str, Any]:
        return {
            key: value
            for key, value in event_dump.items()
            if key not in self._EVENT_ATTRIBUTES_TO_HIDE_IN_IO
        }

    def _create_opik_span_inputs_from_span(
        self, span: Span
    ) -> Optional[Dict[str, Any]]:
        # We consider the serialization of the first event,
        # i.e., the span start event, as the input of the span
        if len(span.events) < 1:
            return {}
        event_dump = span.events[0].model_dump(
            mask_sensitive_information=self.mask_sensitive_information
        )
        return event_dump.get(
            "inputs", self._remove_unnecessary_attributes_for_event_display(event_dump)
        )

    def _create_opik_span_outputs_from_span(
        self, span: Span
    ) -> Optional[Dict[str, Any]]:
        # We consider the serialization of the last non-exception event,
        # i.e., the span end event, as the output of the span
        terminal_event = self._get_terminal_non_error_event(span)
        if terminal_event is None:
            return None
        event_dump = terminal_event.model_dump(
            mask_sensitive_information=self.mask_sensitive_information
        )
        if isinstance(span, LlmGenerationSpan) and "content" in event_dump:
            # We beautify the LlmGenerationSpan outputs, as it is one of the most common ones
            event_dump["outputs"] = {
                "response": event_dump.get("content"),
                "tool_calls": event_dump.get("tool_calls"),
                "completion_id": event_dump.get("completion_id"),
            }
        return event_dump.get(
            "outputs", self._remove_unnecessary_attributes_for_event_display(event_dump)
        )

    def _create_opik_span_usage_from_span(
        self, span: Span
    ) -> Optional[llm_usage.OpikUsage]:
        if isinstance(span, LlmGenerationSpan) and len(span.events) >= 2:
            # The last event of the LlmGenerationSpan when closed should be
            # a LlmGenerationResponse object, in that case we can extract usage
            event = self._get_terminal_non_error_event(span)
            if isinstance(event, LlmGenerationResponse):
                return llm_usage.OpikUsage(
                    provider_usage=llm_usage.unknown_usage.UnknownUsage(),
                    completion_tokens=event.output_tokens,
                    prompt_tokens=event.input_tokens,
                    total_tokens=(
                        event.input_tokens + event.output_tokens
                        if event.input_tokens and event.output_tokens
                        else None
                    ),
                )
        return None

    def _get_terminal_non_error_event(self, span: Span) -> Optional[Event]:
        # Get the terminal event for the span. It's supposed to be the last event
        # (excluding the first) that is not an ExceptionRaised event
        for event in reversed(span.events[1:]):
            if not isinstance(event, ExceptionRaised):
                return event
        return None

    def _create_opik_span_error_info_from_span(
        self, span: Span
    ) -> Optional[opik.types.ErrorInfoDict]:
        # We create an ErrorInfoDict based on the last exception raised, if any
        for event in reversed(span.events):
            if isinstance(event, ExceptionRaised):
                event_dump = event.model_dump(
                    mask_sensitive_information=self.mask_sensitive_information
                )
                error_info: opik.types.ErrorInfoDict = {
                    "exception_type": event_dump["exception_type"],
                    "traceback": event_dump["exception_stacktrace"],
                }
                if event_dump["exception_message"] != "":
                    error_info["message"] = event_dump["exception_message"]
                return error_info

        return None

    def _create_opik_span_metadata_from_span(
        self, span: Span
    ) -> Optional[Dict[str, Any]]:
        return {
            "events": [
                event.model_dump(
                    mask_sensitive_information=self.mask_sensitive_information
                )
                for event in span.events
            ]
        }

    def _get_state_for_active_trace(self) -> Optional[_PerTraceState]:
        trace = get_trace()
        trace_id = trace.id if trace is not None else _ACTIVE_AGENTSPEC_TRACE_ID.get()
        if trace_id is None:
            return None
        with self._states_lock:
            return self._states.get(trace_id)

    def _accumulate_llm_trace_data(
        self, state: _PerTraceState, span: LlmGenerationSpan
    ) -> None:
        """Collect LLM span data to populate trace-level input/output."""
        span_input = self._create_opik_span_inputs_from_span(span)
        span_output = self._create_opik_span_outputs_from_span(span)

        with state.lock:
            if state.first_llm_input is None and span_input:
                state.first_llm_input = span_input

            if span_output is not None:
                state.last_llm_output = span_output

    def on_start(self, span: Span) -> None:
        """
        Handle the start of an AgentSpec span.

        Pre-assign an Opik span ID here so child spans can reference us as a
        parent, but defer the actual span emission to ``on_end`` to send a
        single full-payload message (avoiding the Create+Update race that
        batching introduces for short-lived spans).
        """
        try:
            state = self._get_state_for_active_trace()
            if state is None:
                return
            with state.lock:
                if span.id not in state.span_ids_mapping:
                    state.span_ids_mapping[span.id] = id_helpers.generate_id()
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_start`: {e}")

    def on_end(self, span: Span) -> None:
        """
        Handle the end of an AgentSpec span.

        Emit the Opik span as a single full-payload write using the
        pre-assigned ID so the backend always sees a complete record, even
        with batching enabled.
        """
        try:
            state = self._get_state_for_active_trace()
            if state is None:
                return

            with state.lock:
                opik_span_id = (
                    state.span_ids_mapping.get(span.id) or id_helpers.generate_id()
                )
                state.span_ids_mapping[span.id] = opik_span_id

                parent_span_id = (
                    state.span_ids_mapping.get(span._parent_span.id)
                    if span._parent_span
                    else None
                )

            self.opik_client.span(
                id=opik_span_id,
                trace_id=state.opik_trace_id,
                parent_span_id=parent_span_id,
                name=self._get_opik_span_name(span),
                type=self._get_opik_span_type_from_agentspec_span_type(span),
                start_time=_ns_to_datetime(span.start_time),
                end_time=_ns_to_datetime(span.end_time),
                model=self._get_opik_model_from_agentspec_span(span),
                input=self._create_opik_span_inputs_from_span(span),
                output=self._create_opik_span_outputs_from_span(span),
                usage=self._create_opik_span_usage_from_span(span),
                metadata=self._create_opik_span_metadata_from_span(span),
                error_info=self._create_opik_span_error_info_from_span(span),
            )

            if isinstance(span, LlmGenerationSpan):
                self._accumulate_llm_trace_data(state, span)
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_end`: {e}")

    def on_event(self, event: Event, span: Span) -> None:
        # Nothing to do on events, they are simply part of the span
        pass

    def _resolve_thread_id(self, trace: AgentSpecTrace) -> Optional[str]:
        """Return the thread_id for the given trace.

        ``AgentSpecInstrumentor`` may stash a per-trace thread_id on the trace
        object itself (``_opik_thread_id``); fall back to the processor's
        default ``thread_id`` if none is set.
        """
        return getattr(trace, "_opik_thread_id", None) or self.default_thread_id

    def startup(self) -> None:
        """
        Initialize Opik state when an AgentSpec trace starts.
        """
        try:
            trace = get_trace()
            if trace is None:
                return
            start_time = datetime_helpers.local_timestamp()
            opik_trace = self.opik_client.trace(
                name=trace.name,
                start_time=start_time,
                thread_id=self._resolve_thread_id(trace),
            )
            state = _PerTraceState(
                opik_trace_id=opik_trace.id,
                name=trace.name,
                start_time=start_time,
                thread_id=self._resolve_thread_id(trace),
            )
            with self._states_lock:
                self._states[trace.id] = state
            _ACTIVE_AGENTSPEC_TRACE_ID.set(trace.id)
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.startup`: {e}")

    def shutdown(self) -> None:
        """
        Finalize Opik state when an AgentSpec trace ends.
        """
        # AgentSpec clears its trace contextvar before invoking shutdown, so we
        # rely on our own ContextVar set during startup.
        trace_id = _ACTIVE_AGENTSPEC_TRACE_ID.get()
        if trace_id is None:
            return
        with self._states_lock:
            state = self._states.pop(trace_id, None)
        _ACTIVE_AGENTSPEC_TRACE_ID.set(None)
        if state is None:
            return
        try:
            # Re-send the full trace payload with the same ID instead of calling
            # trace.end(): with batching enabled, an UpdateTraceMessage shortly
            # after CreateTraceMessage can be lost. Re-sending overwrites at the
            # backend. https://www.comet.com/docs/opik/tracing/batching_and_updates
            self.opik_client.trace(
                id=state.opik_trace_id,
                name=state.name,
                start_time=state.start_time,
                end_time=datetime_helpers.local_timestamp(),
                input=state.first_llm_input,
                output=state.last_llm_output,
                thread_id=state.thread_id,
            )
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.shutdown`: {e}")

    # Async methods just call the sync versions

    async def on_start_async(self, span: Span) -> None:
        self.on_start(span)

    async def on_end_async(self, span: Span) -> None:
        self.on_end(span)

    async def on_event_async(self, event: Event, span: Span) -> None:
        self.on_event(event, span)

    async def startup_async(self) -> None:
        self.startup()

    async def shutdown_async(self) -> None:
        self.shutdown()


class AgentSpecInstrumentor(BaseInstrumentor):  # type: ignore
    """Instrument AgentSpec tracing with Opik."""

    def instrumentation_dependencies(self) -> Collection[str]:
        return ["pyagentspec >= 26.1.0"]

    def _instrument(self, **kwargs: Any) -> None:
        project_name = kwargs.get("project_name", None)
        api_key = kwargs.get("api_key", None)
        workspace = kwargs.get("workspace", None)
        host = kwargs.get("host", None)
        mask_sensitive_information = kwargs.get("mask_sensitive_information", True)
        thread_id = kwargs.get("thread_id", None)

        from pyagentspec.tracing.trace import Trace, get_trace

        if get_trace() is not None:
            raise ValueError(
                "Agent Spec Trace already active, instrumentation is not allowed. "
                "Close any existing Agent Spec Trace before instrumenting your code."
            )

        opik_span_processor = OpikSpanProcessor(
            project_name=project_name,
            host=host,
            api_key=api_key,
            workspace=workspace,
            mask_sensitive_information=mask_sensitive_information,
            thread_id=thread_id,
        )
        trace = Trace(span_processors=[opik_span_processor])
        # Attach the thread_id to the trace itself so the processor can resolve
        # it per-trace — important when one processor is shared across traces.
        if thread_id is not None:
            trace._opik_thread_id = thread_id  # type: ignore[attr-defined]
        trace._start()

    def _uninstrument(self, **kwargs: Any) -> None:
        from pyagentspec.tracing.trace import get_trace

        trace = get_trace()
        # Whatever happens we do not crash during the final shutdown, but we warn the user
        if trace is not None:
            try:
                trace._end()
            except Exception as e:
                logger.warning(f"Exception raised during Trace `end`: {e}")

    @contextmanager
    def instrument_context(self, **kwargs: Any) -> Generator[None, Any, None]:
        """
        Instrument AgentSpec tracing for the lifetime of the context manager.

        Args:
            **kwargs: Keyword arguments forwarded to :meth:`instrument`.

        Yields:
            None
        """
        self.instrument(**kwargs)
        try:
            yield
        finally:
            if self.is_instrumented_by_opentelemetry:
                self.uninstrument(**kwargs)
