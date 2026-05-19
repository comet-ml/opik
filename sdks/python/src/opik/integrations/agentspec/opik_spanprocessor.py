import logging
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Collection, Dict, Generator, Optional, Set

from opentelemetry.instrumentation.instrumentor import BaseInstrumentor  # type: ignore
from pyagentspec.tracing.events import Event, ExceptionRaised, LlmGenerationResponse
from pyagentspec.tracing.spans import LlmGenerationSpan, Span, ToolExecutionSpan
from pyagentspec.tracing.trace import get_trace

import opik
import opik.id_helpers as id_helpers
import opik.llm_usage as llm_usage

logger = logging.getLogger(__name__)


def _ns_to_datetime(ns: Optional[int]) -> Optional[datetime]:
    """Convert nanosecond timestamp to a UTC-aware datetime, or None if not set."""
    if not ns:
        return None
    return datetime.fromtimestamp(ns / 1_000_000_000, tz=timezone.utc)


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
            thread_id: Thread ID used to group multiple traces into a conversation thread.
        """
        self.mask_sensitive_information = mask_sensitive_information
        self.opik_client = opik.Opik(
            project_name=project_name, host=host, workspace=workspace, api_key=api_key
        )
        self.thread_id = thread_id
        self.opik_trace: Optional[opik.Trace] = None
        self._trace_name: Optional[str] = None
        self._trace_start_time: Optional[datetime] = None
        # Mapping from Agent Spec Span ID to pre-assigned Opik Span ID
        self.opik_span_ids_mapping: Dict[str, str] = {}
        # Accumulated trace-level data from LLM spans
        self._trace_first_llm_input: Optional[Dict[str, Any]] = None
        self._trace_last_llm_output: Optional[Dict[str, Any]] = None

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

    def _accumulate_llm_trace_data(self, span: LlmGenerationSpan) -> None:
        """Collect LLM span data to populate trace-level input/output."""
        span_input = self._create_opik_span_inputs_from_span(span)
        span_output = self._create_opik_span_outputs_from_span(span)

        if self._trace_first_llm_input is None and span_input:
            self._trace_first_llm_input = span_input

        if span_output is not None:
            self._trace_last_llm_output = span_output

    def on_start(self, span: Span) -> None:
        """
        Handle the start of an AgentSpec span.

        We pre-assign an Opik span ID here so child spans can reference us as a parent,
        but we defer the actual span emission to ``on_end`` to send a single full-payload
        message (avoiding the Create+Update race that batching introduces for short-lived
        spans).
        """
        try:
            if span.id not in self.opik_span_ids_mapping:
                self.opik_span_ids_mapping[span.id] = id_helpers.generate_id()
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_start`: {e}")

    def on_end(self, span: Span) -> None:
        """
        Handle the end of an AgentSpec span.

        Emit the Opik span as a single full-payload write using the pre-assigned ID so
        the backend always sees a complete record, even with batching enabled.
        """
        try:
            if self.opik_trace is None:
                return

            opik_span_id = self.opik_span_ids_mapping.get(span.id) or id_helpers.generate_id()
            self.opik_span_ids_mapping[span.id] = opik_span_id

            parent_span_id = (
                self.opik_span_ids_mapping.get(span._parent_span.id)
                if span._parent_span
                else None
            )

            self.opik_client.span(
                id=opik_span_id,
                trace_id=self.opik_trace.id,
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
                self._accumulate_llm_trace_data(span)
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_end`: {e}")

    def on_event(self, event: Event, span: Span) -> None:
        """
        Handle an event emitted for an AgentSpec span.

        Args:
            event: The event emitted by AgentSpec.
            span: The AgentSpec span associated with the event.

        Returns:
            None
        """
        # Nothing to do on events, they are simply part of the span
        pass

    def startup(self) -> None:
        """
        Initialize Opik state when an AgentSpec trace starts.

        Returns:
            None
        """
        try:
            trace = get_trace()
            self._trace_name = trace.name
            self._trace_start_time = datetime.now(timezone.utc)
            # Cannot use the Agent Spec trace ID, as Opik requires the ID to be UUIDv7 compliant
            self.opik_trace = self.opik_client.trace(
                name=self._trace_name,
                start_time=self._trace_start_time,
                thread_id=self.thread_id,
            )
        except Exception as e:
            self.opik_trace = None
            logger.warning(f"Exception raised during `OpikSpanProcessor.startup`: {e}")

    def shutdown(self) -> None:
        """
        Finalize Opik state when an AgentSpec trace ends.

        Returns:
            None
        """
        try:
            if self.opik_trace is not None:
                # Re-send the full trace payload with the same ID instead of calling
                # trace.end(): with batching enabled, an UpdateTraceMessage shortly after
                # CreateTraceMessage can be lost. Re-sending overwrites at the backend.
                # https://www.comet.com/docs/opik/tracing/batching_and_updates
                self.opik_client.trace(
                    id=self.opik_trace.id,
                    name=self._trace_name,
                    start_time=self._trace_start_time,
                    end_time=datetime.now(timezone.utc),
                    input=self._trace_first_llm_input,
                    output=self._trace_last_llm_output,
                    thread_id=self.thread_id,
                )
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.shutdown`: {e}")
        finally:
            self.opik_trace = None
            self._trace_name = None
            self._trace_start_time = None
            self._trace_first_llm_input = None
            self._trace_last_llm_output = None
            self.opik_span_ids_mapping.clear()

    # Async methods just call the sync versions

    async def on_start_async(self, span: Span) -> None:
        """
        Handle the start of an AgentSpec span asynchronously.

        Args:
            span: The AgentSpec span being started.

        Returns:
            None
        """
        self.on_start(span)

    async def on_end_async(self, span: Span) -> None:
        """
        Handle the end of an AgentSpec span asynchronously.

        Args:
            span: The AgentSpec span being ended.

        Returns:
            None
        """
        self.on_end(span)

    async def on_event_async(self, event: Event, span: Span) -> None:
        """
        Handle an event emitted for an AgentSpec span asynchronously.

        Args:
            event: The event emitted by AgentSpec.
            span: The AgentSpec span associated with the event.

        Returns:
            None
        """
        self.on_event(event, span)

    async def startup_async(self) -> None:
        """
        Initialize Opik state asynchronously when an AgentSpec trace starts.

        Returns:
            None
        """
        self.startup()

    async def shutdown_async(self) -> None:
        """
        Finalize Opik state asynchronously when an AgentSpec trace ends.

        Returns:
            None
        """
        self.shutdown()


class AgentSpecInstrumentor(BaseInstrumentor):  # type: ignore
    """Instrument AgentSpec tracing with Opik."""

    def instrumentation_dependencies(self) -> Collection[str]:
        """
        Return the package dependencies required by this instrumentor.

        Returns:
            A collection of dependency specifiers understood by OpenTelemetry.
        """
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
