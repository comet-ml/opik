import logging
from contextlib import contextmanager
from datetime import datetime
from typing import Any, Collection, Dict, Generator, Optional, Set

from opentelemetry.instrumentation.instrumentor import BaseInstrumentor  # type: ignore
from pyagentspec.tracing.events import Event, ExceptionRaised, LlmGenerationResponse
from pyagentspec.tracing.spanprocessor import SpanProcessor
from pyagentspec.tracing.spans import LlmGenerationSpan, Span, ToolExecutionSpan
from pyagentspec.tracing.trace import get_trace

import opik
import opik.llm_usage as llm_usage

logger = logging.getLogger(__name__)


class OpikSpanProcessor(SpanProcessor):
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
        """
        super().__init__(mask_sensitive_information=mask_sensitive_information)
        self.opik_client = opik.Opik(
            project_name=project_name, host=host, workspace=workspace, api_key=api_key
        )
        self.opik_trace: Optional[opik.Trace] = None
        # Mapping from Agent Spec Span ID to Opik Span ID
        self.opik_span_ids_mapping: Dict[str, str] = {}
        # Collection of Agent Spec Span ID -> Opik Span
        self.opik_spans: Dict[str, opik.Span] = {}

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

    def _get_or_create_opik_span(self, span: Span) -> Optional[opik.Span]:
        # Get the Opik Span corresponding to the given Agent Spec Span if it already exists
        # in the registry, otherwise create one, register it for future gets, and return it
        opik_span = self.opik_spans.get(span.id, None)
        if opik_span is None and self.opik_trace:
            opik_span = self.opik_trace.span(
                # Cannot use the AgentSpec span ID, as Opik requires the ID to be UUIDv7 compliant
                name=span.name,
                parent_span_id=(
                    self.opik_span_ids_mapping.get(span._parent_span.id, None)
                    if span._parent_span
                    else None
                ),
                start_time=(
                    datetime.fromtimestamp(span.start_time / 1_000_000_000)
                    if span.start_time
                    else None
                ),
                end_time=(
                    datetime.fromtimestamp(span.end_time / 1_000_000_000)
                    if span.end_time
                    else None
                ),
                type=self._get_opik_span_type_from_agentspec_span_type(span),
                model=self._get_opik_model_from_agentspec_span(span),
                input=self._create_opik_span_inputs_from_span(span),
                output=self._create_opik_span_outputs_from_span(span),
                usage=self._create_opik_span_usage_from_span(span),
                metadata=self._create_opik_span_metadata_from_span(span),
                error_info=self._create_opik_span_error_info_from_span(span),
            )
            self.opik_span_ids_mapping[span.id] = opik_span.id
            self.opik_spans[span.id] = opik_span
        return opik_span

    def on_start(self, span: Span) -> None:
        """
        Handle the start of an AgentSpec span.

        Args:
            span: The AgentSpec span being started.

        Returns:
            None
        """
        try:
            # Creating the span if it does not exist, we don't need to start it
            self._get_or_create_opik_span(span)
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_start`: {e}")

    def on_end(self, span: Span) -> None:
        """
        Handle the end of an AgentSpec span.

        Args:
            span: The AgentSpec span being ended.

        Returns:
            None
        """
        try:
            opik_span = self._get_or_create_opik_span(span)
            if opik_span is not None:
                # This should only happen if the trace is none, i.e., there's no trace active
                opik_span.end(
                    # Update the span attributes with the new data we have
                    end_time=(
                        datetime.fromtimestamp(span.end_time / 1_000_000_000)
                        if span.end_time
                        else None
                    ),
                    model=self._get_opik_model_from_agentspec_span(span),
                    input=self._create_opik_span_inputs_from_span(span),
                    output=self._create_opik_span_outputs_from_span(span),
                    usage=self._create_opik_span_usage_from_span(span),
                    metadata=self._create_opik_span_metadata_from_span(span),
                    error_info=self._create_opik_span_error_info_from_span(span),
                )
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.on_end`: {e}")
        finally:
            # Remove the span from the internal registries as we don't need it anymore
            self.opik_spans.pop(span.id, None)
            self.opik_span_ids_mapping.pop(span.id, None)

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
            # Cannot use the Agent Spec trace ID, as Opik requires the ID to be UUIDv7 compliant
            self.opik_trace = self.opik_client.trace(name=trace.name)
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
                self.opik_trace.end()
        except Exception as e:
            logger.warning(f"Exception raised during `OpikSpanProcessor.shutdown`: {e}")
        finally:
            self.opik_trace = None

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
