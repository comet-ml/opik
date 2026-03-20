"""
Custom OTel SpanProcessor that bridges AG2's OpenTelemetry spans into Opik traces/spans.

AG2's built-in OTel instrumentor (`autogen.opentelemetry`) generates spans with
attributes like `ag2.span.type`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, etc.
This processor intercepts those spans and creates corresponding Opik trace/span objects,
preserving the full parent-child hierarchy.
"""

import json
import logging
import threading
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from opentelemetry.context import Context
from opentelemetry.sdk.trace import ReadableSpan, Span
from opentelemetry.sdk.trace.export import SpanProcessor
from opentelemetry.trace import format_span_id, format_trace_id

from opik.api_objects import opik_client
from opik.api_objects.span import SpanData
from opik.api_objects.trace import TraceData

LOGGER = logging.getLogger(__name__)

# Mapping from AG2 span types to Opik span types
_AG2_SPAN_TYPE_TO_OPIK: Dict[str, str] = {
    "conversation": "general",
    "multi_conversation": "general",
    "agent": "general",
    "llm": "llm",
    "tool": "tool",
    "handoff": "general",
    "speaker_selection": "general",
    "human_input": "general",
    "code_execution": "tool",
}


def _ns_to_datetime(ns: int) -> datetime:
    """Convert nanosecond timestamp to datetime."""
    return datetime.fromtimestamp(ns / 1e9, tz=timezone.utc)


def _safe_json_loads(value: Any) -> Any:
    """Try to parse a JSON string, return as-is if not valid JSON."""
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, ValueError):
            return value
    return value


class OpikSpanProcessor(SpanProcessor):
    """
    OTel SpanProcessor that converts AG2 spans into Opik traces and spans.

    Root spans (no parent) become Opik traces. Child spans become Opik spans
    linked to their parent trace. AG2 OTel attributes are mapped to Opik fields.
    """

    def __init__(
        self,
        project_name: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._opik_client = opik_client.get_client_cached()
        self._project_name = project_name
        self._tags = tags
        self._metadata = metadata or {}

        # Maps OTel span_id (hex string) -> Opik TraceData or SpanData
        self._span_map: Dict[str, Any] = {}
        # Maps OTel span_id -> Opik trace_id (for linking child spans to traces)
        self._trace_id_map: Dict[str, str] = {}
        self._lock = threading.Lock()

    def on_start(self, span: Span, parent_context: Optional[Context] = None) -> None:
        """Called when an OTel span starts. Creates the corresponding Opik object."""
        ctx = span.get_span_context()
        span_id = format_span_id(ctx.span_id)

        parent = span.parent
        is_root = parent is None or not parent.is_valid

        with self._lock:
            if is_root:
                trace_data = TraceData(
                    name=span.name,
                    project_name=self._project_name,
                    tags=list(self._tags) if self._tags else ["ag2"],
                    metadata={**self._metadata, "created_from": "ag2"},
                )
                self._span_map[span_id] = trace_data
                self._trace_id_map[span_id] = trace_data.id
            else:
                parent_span_id = format_span_id(parent.span_id)
                otel_trace_id = format_trace_id(ctx.trace_id)

                # Find the Opik trace_id by walking up the parent chain
                opik_trace_id = self._resolve_trace_id(
                    parent_span_id, otel_trace_id
                )

                # Determine parent Opik span_id (None if parent is the root trace)
                parent_opik_obj = self._span_map.get(parent_span_id)
                opik_parent_span_id = None
                if isinstance(parent_opik_obj, SpanData):
                    opik_parent_span_id = parent_opik_obj.id

                span_data = SpanData(
                    trace_id=opik_trace_id,
                    parent_span_id=opik_parent_span_id,
                    name=span.name,
                    type="general",
                    project_name=self._project_name,
                )
                self._span_map[span_id] = span_data
                self._trace_id_map[span_id] = opik_trace_id

    def on_end(self, span: ReadableSpan) -> None:
        """Called when an OTel span ends. Finalizes the Opik object with attributes."""
        ctx = span.get_span_context()
        span_id = format_span_id(ctx.span_id)

        with self._lock:
            opik_obj = self._span_map.pop(span_id, None)

        if opik_obj is None:
            return

        attrs = dict(span.attributes or {})
        start_time = _ns_to_datetime(span.start_time) if span.start_time else None
        end_time = _ns_to_datetime(span.end_time) if span.end_time else None

        # Map AG2 span type
        ag2_span_type = attrs.get("ag2.span.type", "")
        opik_span_type = _AG2_SPAN_TYPE_TO_OPIK.get(ag2_span_type, "general")

        # Extract model info
        model = attrs.get("gen_ai.response.model") or attrs.get(
            "gen_ai.request.model"
        )
        provider = attrs.get("gen_ai.provider.name")

        # Extract usage
        usage = self._extract_usage(attrs)

        # Extract input/output
        input_data = self._extract_input(attrs)
        output_data = self._extract_output(attrs)

        # Extract agent name for span name
        agent_name = attrs.get("gen_ai.agent.name")
        name = agent_name if agent_name else span.name

        # Build metadata from remaining AG2 attributes
        span_metadata = dict(self._metadata)
        if ag2_span_type:
            span_metadata["ag2_span_type"] = ag2_span_type
        if attrs.get("gen_ai.operation.name"):
            span_metadata["ag2_operation"] = attrs["gen_ai.operation.name"]

        if isinstance(opik_obj, TraceData):
            opik_obj.name = name
            opik_obj.start_time = start_time
            opik_obj.end_time = end_time
            opik_obj.input = input_data
            opik_obj.output = output_data
            if span_metadata:
                existing = opik_obj.metadata or {}
                existing.update(span_metadata)
                opik_obj.metadata = existing

            self._opik_client.trace(**opik_obj.as_parameters)

        elif isinstance(opik_obj, SpanData):
            opik_obj.name = name
            opik_obj.type = opik_span_type
            opik_obj.start_time = start_time
            opik_obj.end_time = end_time
            opik_obj.input = input_data
            opik_obj.output = output_data
            opik_obj.model = model
            opik_obj.provider = provider
            opik_obj.usage = usage
            if span_metadata:
                opik_obj.metadata = span_metadata

            self._opik_client.span(**opik_obj.as_parameters)

        # Cleanup trace_id mapping
        with self._lock:
            self._trace_id_map.pop(span_id, None)

    def shutdown(self) -> None:
        """Shutdown the processor and flush the Opik client."""
        self._opik_client.flush()

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        """Flush pending data to Opik."""
        self._opik_client.flush()
        return True

    def _resolve_trace_id(self, parent_span_id: str, otel_trace_id: str) -> str:
        """Resolve the Opik trace_id for a child span by walking the parent chain."""
        if parent_span_id in self._trace_id_map:
            return self._trace_id_map[parent_span_id]
        # Fallback: use OTel trace_id as a lookup key
        LOGGER.debug(
            "Could not resolve Opik trace_id for parent %s, "
            "this span may appear disconnected.",
            parent_span_id,
        )
        # Return a placeholder - this shouldn't happen in normal flow
        return otel_trace_id

    def _extract_usage(self, attrs: Dict[str, Any]) -> Optional[Dict[str, int]]:
        """Extract token usage from OTel attributes."""
        input_tokens = attrs.get("gen_ai.usage.input_tokens")
        output_tokens = attrs.get("gen_ai.usage.output_tokens")

        if input_tokens is not None or output_tokens is not None:
            usage: Dict[str, int] = {}
            if input_tokens is not None:
                usage["prompt_tokens"] = int(input_tokens)
            if output_tokens is not None:
                usage["completion_tokens"] = int(output_tokens)
            if "prompt_tokens" in usage and "completion_tokens" in usage:
                usage["total_tokens"] = (
                    usage["prompt_tokens"] + usage["completion_tokens"]
                )
            return usage
        return None

    def _extract_input(self, attrs: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Extract input data from OTel attributes."""
        messages = attrs.get("gen_ai.input.messages")
        if messages is not None:
            return {"messages": _safe_json_loads(messages)}
        return None

    def _extract_output(self, attrs: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Extract output data from OTel attributes."""
        messages = attrs.get("gen_ai.output.messages")
        if messages is not None:
            return {"messages": _safe_json_loads(messages)}
        return None
