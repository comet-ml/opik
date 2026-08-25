"""In-process bridge from Pydantic AI OpenTelemetry spans to Opik."""

import logging
from typing import Any, Dict, List, Optional, Tuple

import opik
from opentelemetry.sdk.trace import ReadableSpan, Span, SpanProcessor

from opik import context_storage
from opik.api_objects.span.span_data import SpanData
from opik.api_objects.trace.trace_data import TraceData
from opik.decorator import arguments_helpers, span_creation_handler

from . import span_data_parsers

LOGGER = logging.getLogger(__name__)


class OpikSpanProcessor(SpanProcessor):
    """Create Opik traces and spans from Pydantic AI OpenTelemetry spans."""

    def __init__(
        self,
        *,
        project_name: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        self._project_name = project_name
        self._metadata = metadata
        self._tags = tags
        self._span_map: Dict[int, Tuple[SpanData, Optional[TraceData]]] = {}

    def on_start(self, span: Span, parent_context: Any = None) -> None:
        try:
            self._handle_start(span)
        except Exception:
            LOGGER.warning("Failed to start Opik span for Pydantic AI", exc_info=True)

    def _handle_start(self, span: Span) -> None:
        parsed = span_data_parsers.parse_span_start_data(
            span.name, span.attributes or {}
        )
        if parsed is None or span.context is None:
            return

        start_arguments = arguments_helpers.StartSpanParameters(
            type=parsed.type,
            name=parsed.name,
            project_name=self._project_name,
            metadata=self._metadata,
            tags=self._tags,
        )
        result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_arguments,
            distributed_trace_headers=None,
        )

        context_storage.add_span_data(result.span_data)
        if result.trace_data is not None:
            context_storage.set_trace_data(result.trace_data)

        try:
            client = opik.get_global_client()
            if client.config.log_start_trace_span:
                client.__internal_api__span__(**result.span_data.as_start_parameters)
                if result.trace_data is not None:
                    client.__internal_api__trace__(
                        **result.trace_data.as_start_parameters
                    )
        except Exception:
            context_storage.pop_span_data(ensure_id=result.span_data.id)
            if result.trace_data is not None:
                context_storage.pop_trace_data(ensure_id=result.trace_data.id)
            raise

        self._span_map[span.context.span_id] = (
            result.span_data,
            result.trace_data,
        )

    def on_end(self, span: ReadableSpan) -> None:
        try:
            self._handle_end(span)
        except Exception:
            LOGGER.warning("Failed to end Opik span for Pydantic AI", exc_info=True)

    def _handle_end(self, span: ReadableSpan) -> None:
        if span.context is None:
            return

        entry = self._span_map.pop(span.context.span_id, None)
        if entry is None:
            return
        span_data, trace_data = entry

        try:
            parsed = span_data_parsers.parse_span_end_data(
                span, span_data.type or "general"
            )
            span_name = self._resolve_span_name(span_data, parsed)

            span_data.init_end_time().update(
                name=span_name,
                input=parsed.input,
                output=parsed.output,
                usage=parsed.usage,
                model=parsed.model,
                provider=parsed.provider,
                metadata=parsed.metadata,
                error_info=parsed.error_info,
                prompts=parsed.prompts,
            )

            client = opik.get_global_client()
            client.__internal_api__span__(**span_data.as_parameters)

            if trace_data is not None:
                trace_data.init_end_time().update(
                    name=span_name,
                    input=parsed.input,
                    output=parsed.output,
                    metadata=parsed.metadata,
                    error_info=parsed.error_info,
                    thread_id=parsed.thread_id,
                )
                client.__internal_api__trace__(**trace_data.as_parameters)
        finally:
            context_storage.pop_span_data(ensure_id=span_data.id)
            if trace_data is not None:
                context_storage.pop_trace_data(ensure_id=trace_data.id)

    @staticmethod
    def _resolve_span_name(
        span_data: SpanData,
        parsed: span_data_parsers.ParsedSpanEndData,
    ) -> Optional[str]:
        if parsed.name:
            return parsed.name
        if span_data.type != "tool":
            return span_data.name

        tool_name = (parsed.input or {}).get("tool_name")
        if not tool_name:
            return span_data.name

        prefix = (span_data.name or "").removesuffix(f" {tool_name}")
        return f"{prefix}: {tool_name}"

    def shutdown(self) -> None:
        return None

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True
