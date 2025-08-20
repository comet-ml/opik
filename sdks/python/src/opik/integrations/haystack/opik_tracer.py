import contextlib
import logging
import os
from typing import Any, Dict, Iterator, List, Optional, Union

from haystack import tracing

import opik.url_helpers as url_helpers
import opik.decorator.tracing_runtime_config as tracing_runtime_config
import opik.decorator.span_creation_handler as span_creation_handler
import opik.decorator.arguments_helpers as arguments_helpers
from opik.api_objects import opik_client
from opik.api_objects import span as opik_span
from opik.api_objects import trace as opik_trace
from opik.types import SpanType

from . import opik_span_bridge
from . import constants

LOGGER = logging.getLogger(__name__)


class OpikTracer(tracing.Tracer):
    """Bridge between Haystack tracer and Opik for tracing pipeline operations."""

    def __init__(
        self,
        opik_client: opik_client.Opik,
        name: str = "Haystack",
        project_name: Optional[str] = None,
    ) -> None:
        """
        Initialize OpikTracer.

        Args:
            opik_client: The Opik client instance.
            name: The name of the pipeline or component.
            project_name: The name of the project for tracing (optional).
        """
        if not tracing.tracer.is_content_tracing_enabled:
            LOGGER.warning(
                "Traces will not be logged to Opik because Haystack tracing is disabled. "
                "To enable, set the HAYSTACK_CONTENT_TRACING_ENABLED environment variable to true "
                "before importing Haystack."
            )
        self._opik_client = opik_client
        self._context: List[opik_span_bridge.OpikSpanBridge] = []
        self._name = name
        self._project_name = project_name
        self.enforce_flush = (
            os.getenv(constants.HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR, "false").lower()
            == "true"
        )

    @contextlib.contextmanager
    def trace(
        self,
        operation_name: str,
        tags: Optional[Dict[str, Any]] = None,
        parent_span: Optional[opik_span_bridge.OpikSpanBridge] = None,
    ) -> Iterator[tracing.Span]:
        tags = tags or {}
        span_name = tags.get(constants.COMPONENT_NAME_KEY, operation_name)

        if parent_span:
            span = self._create_child_span(parent_span, span_name, operation_name, tags)
        else:
            span = self._create_span_or_trace(operation_name, span_name)

        self._context.append(span)

        if tags:
            span.set_tags(tags)

        try:
            yield span
        finally:
            self._finalize_span(span)

    def _create_span_or_trace(
        self, operation_name: str, span_name: str
    ) -> opik_span_bridge.OpikSpanBridge:
        """Create a span or trace based on existing context using span_creation_handler."""
        # For pipeline operations, use the pipeline name, otherwise use component name
        final_name = (
            self._name if operation_name == constants.PIPELINE_RUN_KEY else span_name
        )
        metadata = {"created_from": "haystack", "operation": operation_name}

        # Always use span_creation_handler - it handles existing context properly
        start_span_parameters = arguments_helpers.StartSpanParameters(
            name=final_name,
            type="general",
            metadata=metadata,
            project_name=self._project_name,
        )

        trace_data, span_data = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_span_parameters,
            distributed_trace_headers=None,
        )
        final_span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData] = (
            trace_data if trace_data is not None else span_data
        )

        return opik_span_bridge.OpikSpanBridge(final_span_or_trace_data)

    def _create_child_span(
        self,
        parent_span: opik_span_bridge.OpikSpanBridge,
        span_name: str,
        operation_name: str,
        tags: Dict[str, Any],
    ) -> opik_span_bridge.OpikSpanBridge:
        """Create a child span from a parent span."""
        parent_data = parent_span.get_opik_span_or_trace_data()
        span_type: SpanType = (
            "llm"
            if tags.get(constants.COMPONENT_TYPE_KEY)
            in constants.ALL_SUPPORTED_GENERATORS
            else "general"
        )

        span_data = parent_data.create_child_span_data(
            name=span_name,
            type=span_type,
            metadata={"created_from": "haystack", "operation": operation_name},
        )
        return opik_span_bridge.OpikSpanBridge(span_data)

    def _finalize_span(self, span: opik_span_bridge.OpikSpanBridge) -> None:
        """Finalize span by updating metadata and ending the span."""
        try:
            span_or_trace_data = span.get_opik_span_or_trace_data()
            span.apply_component_metadata()
            span_or_trace_data.init_end_time()

            # Send data to backend if tracing is active
            if tracing_runtime_config.is_tracing_active():
                if isinstance(span_or_trace_data, opik_trace.TraceData):
                    self._opik_client.trace(**span_or_trace_data.as_parameters)
                else:
                    self._opik_client.span(**span_or_trace_data.as_parameters)

            self._context.pop()
            if self.enforce_flush:
                self.flush()
        except Exception as e:
            LOGGER.error("Failed to finalize span: %s", e, exc_info=True)
            if self._context:
                self._context.pop()

    def flush(self) -> None:
        """Flush the Opik client to send pending data."""
        self._opik_client.flush()

    def current_span(self) -> Optional[opik_span_bridge.OpikSpanBridge]:
        """Return the current active span."""
        return self._context[-1] if self._context else None

    def get_project_url(self) -> Optional[str]:
        """Return the URL to the tracing data."""
        span = self.current_span()
        if not span:
            return None

        span_data = span.get_opik_span_or_trace_data()
        trace_id = (
            span_data.id
            if isinstance(span_data, opik_trace.TraceData)
            else span_data.trace_id
        )
        return url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id, url_override=self._opik_client.config.url_override
        )

    def get_trace_id(self) -> Optional[str]:
        """Return the trace id of the current trace."""
        span = self.current_span()
        if not span:
            return None

        span_data = span.get_opik_span_or_trace_data()
        return (
            span_data.id
            if isinstance(span_data, opik_trace.TraceData)
            else span_data.trace_id
        )
